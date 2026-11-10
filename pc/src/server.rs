//! Smooth mode's network side: mDNS so the phone finds this PC, the TCP hello and pairing, sealed events over TCP and
//! sealed moves over UDP. Only the phone of the current session moves this PC, and only while not paused.
use crate::state::{self, Ask, Shared};
use crate::{crypto, input};
use enigo::{Enigo, Settings};
use p256::SecretKey;
use std::io::{Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const PORT: u16 = 47805;
/// How long a session goes without writing to the phone before it sends the heartbeat.
const HEARTBEAT: Duration = Duration::from_secs(2);

struct Session {
    phone: IpAddr,
    keys: crypto::Keys,
    last_udp: u64,
}

pub fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

fn load_key() -> SecretKey {
    let path = state::dir().join("key");
    if let Ok(b) = std::fs::read(&path) {
        if let Ok(k) = SecretKey::from_slice(&b) {
            return k;
        }
    }
    let k = SecretKey::random(&mut rand::rngs::OsRng);
    std::fs::write(&path, k.to_bytes()).expect("can't save this PC's key");
    k
}

fn read_frame(s: &mut TcpStream) -> Option<Vec<u8>> {
    let mut len = [0u8; 2];
    s.read_exact(&mut len).ok()?;
    let mut buf = vec![0u8; u16::from_be_bytes(len) as usize];
    s.read_exact(&mut buf).ok()?;
    Some(buf)
}

fn write_frame(s: &mut TcpStream, b: &[u8]) -> bool {
    s.write_all(&(b.len() as u16).to_be_bytes()).is_ok() && s.write_all(b).is_ok()
}

/// Phone's hello: 'H', version 1, its public key, its nonce, then its name (UTF-8, rest of the frame).
pub fn parse_hello(f: &[u8]) -> Option<(&[u8], &[u8], String)> {
    if f.len() < 2 + crypto::PUB_LEN + crypto::NONCE_LEN || f[0] != b'H' || f[1] != 1 {
        return None;
    }
    let pub_end = 2 + crypto::PUB_LEN;
    let nonce_end = pub_end + crypto::NONCE_LEN;
    Some((&f[2..pub_end], &f[pub_end..nonce_end], String::from_utf8_lossy(&f[nonce_end..]).chars().take(40).collect()))
}

fn handle(mut s: TcpStream, key: &SecretKey, shared: &Shared, session: &Mutex<Option<Session>>, enigo: &Mutex<Enigo>, show: &dyn Fn()) {
    if shared.lock().unwrap().paused {
        return; // paused: the phone stays on Bluetooth
    }
    let phone_ip = match s.peer_addr() { Ok(a) => a.ip(), Err(_) => return };
    let _ = s.set_read_timeout(Some(Duration::from_secs(90)));
    let Some(hello) = read_frame(&mut s) else { return };
    let Some((phone_pub, phone_nonce, name)) = parse_hello(&hello) else { return };
    let phone_key = hex(phone_pub);
    let pc_pub = crypto::public_bytes(key);
    let pc_nonce: [u8; crypto::NONCE_LEN] = rand::random();
    let known = state::is_allowed(&phone_key);
    if known {
        state::rename(&phone_key, &name);
    }
    let mut reply = vec![b'H', 1];
    reply.extend_from_slice(&pc_pub);
    reply.extend_from_slice(&pc_nonce);
    reply.push(known as u8);
    reply.extend_from_slice(hostname::get().map(|h| h.to_string_lossy().into_owned()).unwrap_or_default().as_bytes());
    if !write_frame(&mut s, &reply) {
        return;
    }
    let Some(keys) = crypto::keys(key, phone_pub, phone_nonce, &pc_nonce) else { return };
    if !known {
        let (tx, rx) = std::sync::mpsc::channel();
        let code = crypto::code(phone_pub, &pc_pub, phone_nonce, &pc_nonce);
        shared.lock().unwrap().ask = Some(Ask { name: name.clone(), code, answer: tx });
        show();
        let allowed = rx.recv_timeout(Duration::from_secs(85)).unwrap_or(false);
        shared.lock().unwrap().ask = None;
        if !allowed {
            let _ = write_frame(&mut s, &crypto::seal(&keys.to_phone, crypto::TCP, 1, b"NO"));
            return;
        }
        state::allow(&phone_key, &name);
    }
    if !write_frame(&mut s, &crypto::seal(&keys.to_phone, crypto::TCP, 1, b"OK")) {
        return;
    }
    let _ = s.set_read_timeout(None);
    {
        let mut st = shared.lock().unwrap();
        st.connected = Some(name.clone());
        st.session = s.try_clone().ok().map(|c| (c, phone_key.clone()));
    }
    let to_phone = keys.to_phone.clone();
    *session.lock().unwrap() = Some(Session { phone: phone_ip, keys: crypto::Keys { to_pc: keys.to_pc.clone(), to_phone: keys.to_phone }, last_udp: 0 });
    if let Ok(mut out) = s.try_clone() {
        std::thread::spawn(move || tell_phone(&mut out, &to_phone));
    }
    let mut last = 0u64;
    let mut held = input::Input::default();
    // Pausing or removing this phone in the window shuts the socket down, which ends this loop.
    while let Some(f) = read_frame(&mut s) {
        let Some((n, e)) = crypto::open(&keys.to_pc, crypto::TCP, &f) else { break };
        if n <= last {
            break; // replayed or reordered on a reliable channel: drop the session
        }
        last = n;
        let actions = held.actions(&e);
        input::run(&mut enigo.lock().unwrap(), actions);
    }
    // Never leave a key or button held on this PC when the phone goes.
    input::run(&mut enigo.lock().unwrap(), held.release_all());
    let _ = s.shutdown(std::net::Shutdown::Both); // ends the heartbeat, which holds a clone of this socket
    let mut cur = session.lock().unwrap();
    if cur.as_ref().is_some_and(|c| c.phone == phone_ip) {
        *cur = None;
        let mut st = shared.lock().unwrap();
        st.connected = None;
        st.session = None;
    }
}

/// Whether the window in front is out of reach of simulated input ([admin_front]), kept current by Windows' own
/// notices ([watch_front]); the condvar wakes each session's writer when it changes.
static FRONT: (std::sync::Mutex<bool>, std::sync::Condvar) = (std::sync::Mutex::new(false), std::sync::Condvar::new());

fn front_changed() {
    let now = admin_front();
    let mut admin = FRONT.0.lock().unwrap();
    if *admin != now {
        *admin = now;
        FRONT.1.notify_all();
    }
}

unsafe extern "system" fn on_front(_: windows_sys::Win32::UI::Accessibility::HWINEVENTHOOK, _: u32, _: windows_sys::Win32::Foundation::HWND, _: i32, _: i32, _: u32, _: u32) {
    front_changed();
}

/// Windows tells this thread when another window comes to the front, or the UAC prompt or lock screen takes over;
/// nothing runs in between (no polling).
fn watch_front() {
    use windows_sys::Win32::UI::Accessibility::SetWinEventHook;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    std::thread::spawn(|| unsafe {
        for event in [EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_DESKTOPSWITCH] {
            SetWinEventHook(event, event, 0, Some(on_front), 0, 0, WINEVENT_OUTOFCONTEXT);
        }
        front_changed();
        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, 0, 0, 0) > 0 {
            DispatchMessageW(&msg);
        }
    });
}

/// The only writer to the phone once allowed, so frames never interleave: "E1"/"E0" when an administrator window comes
/// to the front or leaves (the phone may then offer Bluetooth; it routes nothing by itself), and an empty frame after
/// [HEARTBEAT] without anything else. Ends when the socket closes.
fn tell_phone(out: &mut TcpStream, to_phone: &aes_gcm::Aes256Gcm) {
    let (mut counter, mut told) = (1u64, false);
    loop {
        let admin = {
            let guard = FRONT.0.lock().unwrap();
            *FRONT.1.wait_timeout_while(guard, HEARTBEAT, |admin| *admin == told).unwrap().0
        };
        let frame = if admin != told {
            told = admin;
            counter += 1;
            crypto::seal(to_phone, crypto::TCP, counter, if admin { b"E1" } else { b"E0" })
        } else {
            Vec::new()
        };
        if !write_frame(out, &frame) {
            return;
        }
    }
}

/// Whether Windows refuses this program's simulated clicks and keys in the window in front: an app run as
/// administrator (Task Manager, for an administrator), the UAC prompt or the lock screen. A process this program can't
/// even open (a protected one) counts as reachable: wrongly calling it blocked would mislead more than missing it.
fn admin_front() -> bool {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY, TokenElevation};
    use windows_sys::Win32::System::Threading::{OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION};
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};
    unsafe {
        let w = GetForegroundWindow();
        if w == 0 {
            return true; // the UAC prompt or the lock screen: another desktop
        }
        let mut pid = 0;
        GetWindowThreadProcessId(w, &mut pid);
        let p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if p == 0 {
            return false;
        }
        let mut token = 0;
        let opened = OpenProcessToken(p, TOKEN_QUERY, &mut token) != 0;
        CloseHandle(p);
        if !opened {
            return true; // its token is closed to this program: a higher one
        }
        let mut e = TOKEN_ELEVATION { TokenIsElevated: 0 };
        let mut len = 0;
        let ok = GetTokenInformation(token, TokenElevation, &mut e as *mut _ as *mut _, std::mem::size_of::<TOKEN_ELEVATION>() as u32, &mut len) != 0;
        CloseHandle(token);
        ok && e.TokenIsElevated != 0
    }
}

fn advertise() -> Option<mdns_sd::ServiceDaemon> {
    let mdns = mdns_sd::ServiceDaemon::new().ok()?;
    let host = hostname::get().ok()?.to_string_lossy().into_owned();
    let info = mdns_sd::ServiceInfo::new("_controlled._tcp.local.", &host, &format!("{host}.local."), "", PORT, None)
        .ok()?
        .enable_addr_auto();
    mdns.register(info).ok()?;
    Some(mdns)
}

/// Starts the network side in background threads. `show` opens the window (for a pairing to allow).
pub fn start(udps: Vec<UdpSocket>, listeners: Vec<TcpListener>, shared: Shared, show: Arc<dyn Fn() + Send + Sync>) {
    let key = Arc::new(load_key());
    let session: Arc<Mutex<Option<Session>>> = Arc::new(Mutex::new(None));
    let enigo = Arc::new(Mutex::new(Enigo::new(&Settings::default()).expect("input injection unavailable")));
    std::mem::forget(advertise()); // lives as long as the program
    watch_front();
    for udp in udps {
        let (session, enigo, shared) = (session.clone(), enigo.clone(), shared.clone());
        std::thread::spawn(move || {
            let mut buf = [0u8; 512];
            loop {
                let Ok((n, from)) = udp.recv_from(&mut buf) else { continue };
                if shared.lock().unwrap().paused {
                    continue;
                }
                let mut cur = session.lock().unwrap();
                let Some(s) = cur.as_mut().filter(|s| s.phone == from.ip()) else { continue };
                let Some((counter, e)) = crypto::open(&s.keys.to_pc, crypto::UDP, &buf[..n]) else { continue };
                if counter <= s.last_udp {
                    continue; // late or replayed: the next move carries the motion
                }
                s.last_udp = counter;
                drop(cur);
                if matches!(e.first(), Some(b'M') | Some(b'S')) {
                    let actions = input::Input::default().actions(&e);
                    input::run(&mut enigo.lock().unwrap(), actions);
                }
            }
        });
    }
    for listener in listeners {
        let (key, shared, session, enigo, show) = (key.clone(), shared.clone(), session.clone(), enigo.clone(), show.clone());
        std::thread::spawn(move || {
            for s in listener.incoming().flatten() {
                let (key, shared, session, enigo, show) = (key.clone(), shared.clone(), session.clone(), enigo.clone(), show.clone());
                std::thread::spawn(move || handle(s, &key, &shared, &session, &enigo, &*show));
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::parse_hello;

    #[test]
    fn hello_needs_the_whole_header() {
        let mut f = vec![b'H', 1];
        f.extend_from_slice(&[4u8; 65]);
        f.extend_from_slice(&[9u8; 16]);
        f.extend_from_slice("Pixel".as_bytes());
        let (p, n, name) = parse_hello(&f).unwrap();
        assert_eq!((p.len(), n.len(), name.as_str()), (65, 16, "Pixel"));
        assert!(parse_hello(&f[..80]).is_none());
        f[1] = 2;
        assert!(parse_hello(&f).is_none());
    }
}
