//! The Bluetooth door (docs/plans/pc-bluetooth-rendezvous.md): an RFCOMM channel listed in this PC's Bluetooth
//! services under [SERVICE], so a phone already paired with this PC opens it directly, with no search by name and no
//! network port. Only paired phones get in: Windows requires the pairing's encryption on RFCOMM.
use std::io::{Read, Write};
use std::time::Duration;
use windows_sys::Win32::Devices::Bluetooth::{AF_BTH, BTHPROTO_RFCOMM, NS_BTH, SOCKADDR_BTH};
use windows_sys::Win32::Networking::WinSock::*;

/// Controlled's service id, also in the phone's SmoothLink: 6c0a3b2e-7e1d-4f6a-9b1e-2f0c0a7e5d11.
const SERVICE: windows_sys::core::GUID = windows_sys::core::GUID::from_u128(0x6c0a3b2e_7e1d_4f6a_9b1e_2f0c0a7e5d11);
const BT_PORT_ANY: u32 = u32::MAX;
const SOL_RFCOMM: i32 = 0x03;
const SO_BTH_AUTHENTICATE: i32 = 0x8000_0001u32 as i32;

/// One phone's RFCOMM connection, read and written like a TCP stream; closed when dropped.
pub struct Stream(SOCKET);

impl Stream {
    /// How long a read waits before failing (None: forever).
    pub fn set_read_timeout(&self, t: Option<Duration>) {
        let ms: u32 = t.map_or(0, |t| t.as_millis() as u32);
        unsafe { setsockopt(self.0, SOL_SOCKET, SO_RCVTIMEO, &ms as *const u32 as *const u8, 4) };
    }
}

impl Read for Stream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = unsafe { recv(self.0, buf.as_mut_ptr(), buf.len().min(i32::MAX as usize) as i32, 0) };
        if n < 0 { Err(std::io::Error::from_raw_os_error(unsafe { WSAGetLastError() })) } else { Ok(n as usize) }
    }
}

impl Write for Stream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let n = unsafe { send(self.0, buf.as_ptr(), buf.len().min(i32::MAX as usize) as i32, 0) };
        if n < 0 { Err(std::io::Error::from_raw_os_error(unsafe { WSAGetLastError() })) } else { Ok(n as usize) }
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        unsafe { closesocket(self.0) };
    }
}

/// How a [door] ended: due for its refresh, or the radio went away.
enum Door {
    Refresh,
    Down,
}

/// How often the door is closed and opened again, listing included. 2026-11-11: re-pairing the phone while the program
/// ran made Windows drop the listing, and phones found nothing until the program restarted; a fresh door every few
/// seconds puts it back on its own (a phone that knocks in the gap tries again).
const REFRESH: Duration = Duration::from_secs(10);

/// Listens on an RFCOMM channel (the last one again if it's still free, so the listing doesn't move), lists it in this
/// PC's Bluetooth services and hands each phone that opens it to [on_phone], until [REFRESH] passes or the radio goes
/// off; then takes the listing down. None when it couldn't open: this PC has no Bluetooth radio, or it's off.
fn door(on_phone: &(impl Fn(Stream) + Send + Sync + Clone + 'static), available: &impl Fn(bool), channel: &mut u32) -> Option<Door> {
    unsafe {
        let open = |port: u32| -> Option<(Stream, SOCKADDR_BTH)> {
            let s = socket(AF_BTH as i32, SOCK_STREAM, BTHPROTO_RFCOMM as i32);
            if s == INVALID_SOCKET {
                return None;
            }
            let s = Stream(s); // closes it on any return
            let a = SOCKADDR_BTH { addressFamily: AF_BTH, btAddr: 0, serviceClassId: std::mem::zeroed(), port };
            // Only devices paired with this PC: Windows asks for the pairing's authentication before a phone gets in.
            let on: u32 = 1;
            setsockopt(s.0, SOL_RFCOMM, SO_BTH_AUTHENTICATE, &on as *const u32 as *const u8, 4);
            let len = std::mem::size_of::<SOCKADDR_BTH>() as i32;
            (bind(s.0, &a as *const _ as *const SOCKADDR, len) == 0 && listen(s.0, 4) == 0).then_some((s, a))
        };
        let Some((s, mut a)) = (if *channel != 0 { open(*channel) } else { None }).or_else(|| open(BT_PORT_ANY)) else { return None };
        let mut len = std::mem::size_of::<SOCKADDR_BTH>() as i32;
        getsockname(s.0, &mut a as *mut _ as *mut SOCKADDR, &mut len);
        *channel = a.port;
        let mut name: Vec<u16> = "Controlled\0".encode_utf16().collect();
        let mut service = SERVICE;
        let mut addr = CSADDR_INFO {
            LocalAddr: SOCKET_ADDRESS { lpSockaddr: &mut a as *mut _ as *mut SOCKADDR, iSockaddrLength: len },
            RemoteAddr: SOCKET_ADDRESS { lpSockaddr: std::ptr::null_mut(), iSockaddrLength: 0 },
            iSocketType: SOCK_STREAM as i32,
            iProtocol: BTHPROTO_RFCOMM as i32,
        };
        let mut q: WSAQUERYSETW = std::mem::zeroed();
        q.dwSize = std::mem::size_of::<WSAQUERYSETW>() as u32;
        q.lpszServiceInstanceName = name.as_mut_ptr();
        q.lpServiceClassId = &mut service;
        q.dwNameSpace = NS_BTH;
        q.dwNumberOfCsAddrs = 1;
        q.lpcsaBuffer = &mut addr;
        if WSASetServiceW(&q, RNRSERVICE_REGISTER, 0) != 0 {
            return None;
        }
        available(true);
        // Non-blocking, looked at every 200 ms until the refresh is due: no select() on a Bluetooth socket (a select-based
        // check misread an open channel on the laptop, 2026-11-11).
        let mut on: u32 = 1;
        ioctlsocket(s.0, FIONBIO, &mut on);
        let until = std::time::Instant::now() + REFRESH;
        let mut failed = 0;
        let end = loop {
            if failed >= 5 {
                break Door::Down; // one device that aborts or fails authentication isn't the radio going off; five in a row is
            }
            if std::time::Instant::now() >= until {
                break Door::Refresh;
            }
            let c = accept(s.0, std::ptr::null_mut(), std::ptr::null_mut());
            if c == INVALID_SOCKET {
                if WSAGetLastError() != WSAEWOULDBLOCK {
                    failed += 1;
                }
                std::thread::sleep(Duration::from_millis(200));
                continue;
            }
            failed = 0;
            // The phone's own socket blocks as usual (it inherits non-blocking from the door).
            let mut off: u32 = 0;
            ioctlsocket(c, FIONBIO, &mut off);
            let on_phone = on_phone.clone();
            std::thread::spawn(move || on_phone(Stream(c)));
        };
        WSASetServiceW(&q, RNRSERVICE_DELETE, 0);
        Some(end)
    }
}

/// Keeps Controlled's Bluetooth door ([door]) open for as long as the program runs, opening it afresh every [REFRESH].
/// While Bluetooth is off (or this PC has none) it tries again every few seconds and says so through [available].
pub fn serve(on_phone: impl Fn(Stream) + Send + Sync + Clone + 'static, available: impl Fn(bool) + Send + 'static) {
    std::thread::spawn(move || {
        // Winsock once for the program's life (another call per door would only raise its count).
        unsafe {
            let mut data: WSADATA = std::mem::zeroed();
            WSAStartup(0x0202, &mut data);
        }
        let mut channel = 0;
        loop {
            match door(&on_phone, &available, &mut channel) {
                Some(Door::Refresh) => {}
                Some(Door::Down) => std::thread::sleep(Duration::from_secs(1)),
                None => {
                    available(false);
                    channel = 0;
                    std::thread::sleep(Duration::from_secs(5));
                }
            }
        }
    });
}
