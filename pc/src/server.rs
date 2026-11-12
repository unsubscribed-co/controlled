//! Smooth mode's connection (docs/plans/pc-bluetooth-rendezvous.md). The phone, already paired with this PC, opens
//! Controlled's Bluetooth channel (bt.rs): the hello and, the first time, the 6-digit code go there. The phone then
//! says where it listens on Wi-Fi, and this PC dials it: sealed events over that TCP connection, sealed moves over UDP
//! this PC opens too. Everything starts from this PC, so no port here is open to the network and no firewall rule is
//! needed, on any network. Only the phone of the current session moves this PC, and only while not paused.
use crate::state::{self, Ask, Shared};
use crate::{bt, crypto, input};
use enigo::{Enigo, Settings};
use p256::SecretKey;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

/// How long a session goes without writing to the phone before it sends the heartbeat.
const HEARTBEAT: Duration = Duration::from_secs(2);
/// How long dialing the phone over Wi-Fi may take before this PC tells it why not.
const DIAL: Duration = Duration::from_secs(3);

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

fn read_frame(s: &mut impl Read) -> Option<Vec<u8>> {
    let mut len = [0u8; 2];
    s.read_exact(&mut len).ok()?;
    let mut buf = vec![0u8; u16::from_be_bytes(len) as usize];
    s.read_exact(&mut buf).ok()?;
    Some(buf)
}

fn write_frame(s: &mut impl Write, b: &[u8]) -> bool {
    let mut f = (b.len() as u16).to_be_bytes().to_vec();
    f.extend_from_slice(b);
    s.write_all(&f).is_ok() // one write: an RFCOMM send per frame
}

/// Phone's hello: 'H', version 2, its public key, its nonce, then its name (UTF-8, rest of the frame).
pub fn parse_hello(f: &[u8]) -> Option<(&[u8], &[u8], String)> {
    if f.len() < 2 + crypto::PUB_LEN + crypto::NONCE_LEN || f[0] != b'H' || f[1] != 2 {
        return None;
    }
    let pub_end = 2 + crypto::PUB_LEN;
    let nonce_end = pub_end + crypto::NONCE_LEN;
    Some((&f[2..pub_end], &f[pub_end..nonce_end], String::from_utf8_lossy(&f[nonce_end..]).chars().take(40).collect()))
}

/// Where the phone waits for this PC: 'W', its TCP port, its UDP port, then each of its IPv4 addresses (Wi-Fi first;
/// also its hotspot or Ethernet, if any), 1 to 8 of them.
pub fn parse_where(w: &[u8]) -> Option<(u16, u16, Vec<Ipv4Addr>)> {
    if w.len() < 9 || w.len() > 5 + 4 * 8 || (w.len() - 5) % 4 != 0 || w[0] != b'W' {
        return None;
    }
    let ips = w[5..].chunks(4).map(|c| Ipv4Addr::new(c[0], c[1], c[2], c[3])).collect();
    Some((u16::from_be_bytes([w[1], w[2]]), u16::from_be_bytes([w[3], w[4]]), ips))
}

/// Dials all of the phone's addresses at once; the first that answers wins.
fn dial(ips: &[Ipv4Addr], port: u16) -> Option<TcpStream> {
    let (tx, rx) = std::sync::mpsc::channel();
    for ip in ips {
        let (tx, to) = (tx.clone(), SocketAddr::from((*ip, port)));
        std::thread::spawn(move || {
            let _ = tx.send(TcpStream::connect_timeout(&to, DIAL).ok());
        });
    }
    drop(tx);
    rx.iter().flatten().next()
}

/// The address this PC reaches [to] from, as Windows' routing picks it (0.0.0.0 when it has no way there): the phone
/// compares it with its own to say why smooth mode can't connect.
fn route_from(to: SocketAddr) -> [u8; 4] {
    let from = UdpSocket::bind("0.0.0.0:0").and_then(|u| u.connect(to).and_then(|_| u.local_addr()));
    match from.map(|a| a.ip()) {
        Ok(std::net::IpAddr::V4(ip)) => ip.octets(),
        _ => [0; 4],
    }
}

/// One phone, from its Bluetooth hello to the end of its session. The counters of each direction run across the
/// Bluetooth and Wi-Fi legs: PC to phone OK (or NO, or F) is 1, the Wi-Fi proof 2, then 'E' frames; phone to PC 'W'
/// is 2, then its events.
fn handle(mut bt: bt::Stream, key: &SecretKey, shared: &Shared, enigo: &Arc<Mutex<Enigo>>, show: &dyn Fn()) {
    bt.set_read_timeout(Some(Duration::from_secs(10)));
    let Some(hello) = read_frame(&mut bt) else { return };
    let Some((phone_pub, phone_nonce, name)) = parse_hello(&hello) else { return };
    let phone_key = hex(phone_pub);
    // Said plainly, before any keys, so the phone tells the person instead of retrying in silence: paused here ('Z'),
    // or another phone has smooth mode right now ('B'; the same phone coming back replaces its own stale session).
    let pc_pub = crypto::public_bytes(key);
    let pc_nonce: [u8; crypto::NONCE_LEN] = rand::random();
    let known = state::is_allowed(&phone_key);
    let code = crypto::code(phone_pub, &pc_pub, phone_nonce, &pc_nonce);
    let (tx, rx) = std::sync::mpsc::channel();
    // The check and, for a new phone, taking the pairing question, under one lock: two phones arriving together
    // can't both pass. The same phone asking again (it left the PC's screen and came back) replaces its own question.
    let refusal = {
        let mut st = shared.lock().unwrap();
        let other = |k: &String| *k != phone_key;
        if st.paused {
            Some(b'Z')
        } else if st.session.as_ref().is_some_and(|(_, k)| other(k)) || st.ask.as_ref().is_some_and(|a| other(&a.key)) {
            Some(b'B') // another phone has smooth mode, or is being allowed right now
        } else {
            if !known {
                st.ask = Some(Ask { key: phone_key.clone(), name: name.clone(), code: code.clone(), answer: tx });
            }
            None
        }
    };
    if let Some(r) = refusal {
        let _ = write_frame(&mut bt, &[r]);
        return;
    }
    if known {
        state::rename(&phone_key, &name);
    }
    let mut reply = vec![b'H', 2];
    reply.extend_from_slice(&pc_pub);
    reply.extend_from_slice(&pc_nonce);
    reply.push(known as u8);
    reply.extend_from_slice(hostname::get().map(|h| h.to_string_lossy().into_owned()).unwrap_or_default().as_bytes());
    let clear_ask = || {
        let mut st = shared.lock().unwrap();
        if st.ask.as_ref().is_some_and(|a| a.code == code) {
            st.ask = None; // only this question: a newer one stays on screen
        }
    };
    let keys = crypto::keys(key, phone_pub, phone_nonce, &pc_nonce);
    if !write_frame(&mut bt, &reply) || keys.is_none() {
        clear_ask();
        return;
    }
    let keys = keys.unwrap();
    if !known {
        show();
        // Up to 85 s for the person (a newer question from the same phone ends this one at once). Not watching for the
        // phone leaving: on a laptop (2026-11-11) that check misread an open channel as closed after a second and
        // answered No by itself, so the code never showed.
        let allowed = rx.recv_timeout(Duration::from_secs(85)).unwrap_or(false);
        clear_ask();
        if !allowed {
            let _ = write_frame(&mut bt, &crypto::seal(&keys.to_phone, crypto::TCP, 1, b"NO"));
            return;
        }
        state::allow(&phone_key, &name);
    }
    if !write_frame(&mut bt, &crypto::seal(&keys.to_phone, crypto::TCP, 1, b"OK")) {
        return;
    }
    let Some((mut last, (tcp_port, udp_port, ips))) =
        read_frame(&mut bt).and_then(|f| crypto::open(&keys.to_pc, crypto::TCP, &f)).and_then(|(n, w)| Some((n, parse_where(&w)?)))
    else {
        return;
    };
    let Some(mut s) = dial(&ips, tcp_port) else {
        // Can't reach it: say from where, so the phone tells the person what's wrong (another network, a VPN, or one
        // that keeps devices apart) instead of staying silent.
        let mut f = vec![b'F'];
        f.extend_from_slice(&route_from((ips[0], tcp_port).into()));
        let _ = write_frame(&mut bt, &crypto::seal(&keys.to_phone, crypto::TCP, 2, &f));
        return;
    };
    drop(bt); // met: everything else goes over Wi-Fi
    let _ = s.set_nodelay(true);
    // The phone's moves come from the address that answered: Windows lets in only what answers this PC's own packets.
    let Some(udp) = s.peer_addr().ok().and_then(|phone| UdpSocket::bind("0.0.0.0:0").and_then(|u| u.connect((phone.ip(), udp_port)).map(|_| u)).ok())
    else {
        return;
    };
    // The proof this is the PC the phone met over Bluetooth, then the first UDP packet, which lets the phone's moves in
    // (Windows lets in what answers a packet this PC sent).
    if !write_frame(&mut s, &crypto::seal(&keys.to_phone, crypto::TCP, 2, b"P")) || udp.send(&crypto::seal(&keys.to_phone, crypto::UDP, 1, b"U")).is_err() {
        return;
    }
    {
        let mut st = shared.lock().unwrap();
        // Paused, or this phone removed, while it was meeting this PC: no session.
        if st.paused || !state::is_allowed(&phone_key) {
            return;
        }
        st.end_session(None); // one phone at a time: this one (another phone was refused at its hello)
        st.connected = Some(name.clone());
        st.session = s.try_clone().ok().map(|c| (c, phone_key.clone()));
    }
    let done = Arc::new(AtomicBool::new(false));
    if let (Ok(mut out), Ok(beat)) = (s.try_clone(), udp.try_clone()) {
        let to_phone = keys.to_phone.clone();
        std::thread::spawn(move || tell_phone(&mut out, &to_phone, &beat));
    }
    {
        let (to_pc, enigo, done, shared) = (keys.to_pc.clone(), enigo.clone(), done.clone(), shared.clone());
        let _ = udp.set_read_timeout(Some(Duration::from_secs(1)));
        std::thread::spawn(move || moves(&udp, &to_pc, &enigo, &done, &shared));
    }
    let mut held = input::Input::default();
    // The phone sends an empty frame every 2 s when idle: three missed and it's gone (asleep, off the Wi-Fi), so
    // anything it held is let go within seconds, not when TCP gives up minutes later.
    let _ = s.set_read_timeout(Some(HEARTBEAT * 3));
    // Pausing or removing this phone in the window shuts the socket down, which ends this loop.
    while let Some(f) = read_frame(&mut s) {
        if f.is_empty() {
            continue; // the phone's heartbeat
        }
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
    done.store(true, Ordering::Relaxed);
    let _ = s.shutdown(std::net::Shutdown::Both); // ends the heartbeat, which holds a clone of this socket
    let mut st = shared.lock().unwrap();
    if st.session.as_ref().is_some_and(|(c, _)| c.local_addr().ok() == s.local_addr().ok()) {
        st.connected = None;
        st.session = None;
    }
}

/// The session's moves and scroll: sealed UDP from the phone, on a socket connected to it, so nothing else gets in.
fn moves(udp: &UdpSocket, to_pc: &aes_gcm::Aes256Gcm, enigo: &Mutex<Enigo>, done: &AtomicBool, shared: &Shared) {
    let (mut buf, mut last) = ([0u8; 512], 0u64);
    while !done.load(Ordering::Relaxed) {
        let Ok(n) = udp.recv(&mut buf) else { continue }; // a timeout, to look at `done` again
        if shared.lock().unwrap().paused {
            continue;
        }
        let Some((counter, e)) = crypto::open(to_pc, crypto::UDP, &buf[..n]) else { continue };
        if counter <= last {
            continue; // late or replayed: the next move carries the motion
        }
        last = counter;
        if matches!(e.first(), Some(b'M') | Some(b'S')) {
            let actions = input::Input::default().actions(&e);
            input::run(&mut enigo.lock().unwrap(), actions);
        }
    }
}

/// What's in front, as the phone's 'E' frames say it: a normal window, one run as administrator (simulated clicks and
/// keys don't reach it), or Windows' secure screen (Ctrl+Alt+Del, lock, UAC: the pointer doesn't either).
const NORMAL: u8 = b'0';
const ADMIN: u8 = b'1';
const SECURE: u8 = b'2';

/// What's in front ([front]), kept current by Windows' own
/// notices ([watch_front]); the condvar wakes each session's writer when it changes.
static FRONT: (std::sync::Mutex<u8>, std::sync::Condvar) = (std::sync::Mutex::new(NORMAL), std::sync::Condvar::new());

/// The clipboard's change count as the timer last saw it ([watch_front]).
static CLIP_SEEN: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

fn front_changed() {
    let now = front();
    let mut current = FRONT.0.lock().unwrap();
    if *current != now {
        *current = now;
        FRONT.1.notify_all();
    }
}

unsafe extern "system" fn on_front(_: windows_sys::Win32::UI::Accessibility::HWINEVENTHOOK, _: u32, _: windows_sys::Win32::Foundation::HWND, _: i32, _: i32, _: u32, _: u32) {
    front_changed();
}

/// Windows tells this thread when another window comes to the front, or the UAC prompt or lock screen takes over; the
/// window under the pointer is checked on a timer.
fn watch_front() {
    use windows_sys::Win32::UI::Accessibility::SetWinEventHook;
    use windows_sys::Win32::UI::WindowsAndMessaging::*;
    std::thread::spawn(|| unsafe {
        for event in [EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_DESKTOPSWITCH] {
            SetWinEventHook(event, event, 0, Some(on_front), 0, 0, WINEVENT_OUTOFCONTEXT);
        }
        front_changed();
        // The window under the pointer has no notice of its own: looked at 4 times a second (see [front]).
        SetTimer(0, 0, 250, None);
        let mut msg: MSG = std::mem::zeroed();
        while GetMessageW(&mut msg, 0, 0, 0) > 0 {
            if msg.message == WM_TIMER {
                front_changed();
                // A copy on this PC wakes the sessions too, so the phone gets it within a quarter second.
                let now = crate::clip::changes();
                if CLIP_SEEN.swap(now, std::sync::atomic::Ordering::Relaxed) != now {
                    FRONT.1.notify_all();
                }
            }
            DispatchMessageW(&msg);
        }
    });
}

/// The only writer to the phone once allowed, so frames never interleave: first "C1" (paste and erase are understood),
/// then "E0"/"E1"/"E2" when what's in front changes, "K" and the text when the person copies text here ([clip::copied])
/// (the phone may then offer Bluetooth; it routes nothing by itself), and an empty frame after
/// [HEARTBEAT] without anything else, plus a UDP one that keeps Windows letting the phone's moves in. Ends when the
/// socket closes.
fn tell_phone(out: &mut TcpStream, to_phone: &aes_gcm::Aes256Gcm, udp: &UdpSocket) {
    let (mut counter, mut told, mut beats) = (2u64, NORMAL, 1u64); // 2: the Wi-Fi proof's
    let mut copies = crate::clip::changes(); // what was copied before the phone came stays here
    // What this PC takes beyond keys and text: 1 = paste and erase. A phone that never hears it types and deletes key by key.
    counter += 1;
    if !write_frame(out, &crypto::seal(to_phone, crypto::TCP, counter, &[b'C', 1])) {
        return;
    }
    loop {
        beats += 1;
        let _ = udp.send(&crypto::seal(to_phone, crypto::UDP, beats, b"U"));
        let now = {
            let guard = FRONT.0.lock().unwrap();
            *FRONT.1.wait_timeout_while(guard, HEARTBEAT, |front| *front == told && crate::clip::changes() == copies).unwrap().0
        };
        let copied = crate::clip::changes();
        let frame = if now != told {
            told = now;
            counter += 1;
            crypto::seal(to_phone, crypto::TCP, counter, &[b'E', now])
        } else if copied != copies {
            copies = copied;
            match crate::clip::copied() {
                Some(t) => {
                    counter += 1;
                    crypto::seal(to_phone, crypto::TCP, counter, &[&[b'K'][..], t.as_bytes()].concat())
                }
                None => Vec::new(),
            }
        } else {
            Vec::new()
        };
        if !write_frame(out, &frame) {
            let _ = out.shutdown(std::net::Shutdown::Both); // the phone is gone: end the session now
            return;
        }
    }
}

/// What's in front: Windows' secure screen (Ctrl+Alt+Del, lock, UAC: no foreground window on this desktop), an app run
/// as administrator (Task Manager, for an administrator) in front or under the pointer, or anything else. Under the
/// pointer too: 2026-11-11 an administrator's terminal behind another window took the pointer but not its clicks, so it
/// could never come to the front and Bluetooth was never offered.
fn front() -> u8 {
    use windows_sys::Win32::Foundation::POINT;
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetAncestor, GetCursorPos, GetForegroundWindow, WindowFromPoint, GA_ROOT};
    unsafe {
        let w = GetForegroundWindow();
        if w == 0 {
            return SECURE;
        }
        if elevated(w) {
            return ADMIN;
        }
        let mut at = POINT { x: 0, y: 0 };
        let under = if GetCursorPos(&mut at) != 0 { GetAncestor(WindowFromPoint(at), GA_ROOT) } else { 0 };
        if under != 0 && under != w && elevated(under) { ADMIN } else { NORMAL }
    }
}

/// The window's program runs as administrator. One this program can't even open (a protected one) counts as not:
/// wrongly calling it blocked would mislead more than missing it.
fn elevated(w: windows_sys::Win32::Foundation::HWND) -> bool {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::Security::{GetTokenInformation, TOKEN_ELEVATION, TOKEN_QUERY, TokenElevation};
    use windows_sys::Win32::System::Threading::{OpenProcess, OpenProcessToken, PROCESS_QUERY_LIMITED_INFORMATION};
    use windows_sys::Win32::UI::WindowsAndMessaging::GetWindowThreadProcessId;
    unsafe {
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

/// Starts smooth mode's side in background threads. `show` opens the window (for a pairing to allow).
pub fn start(shared: Shared, show: Arc<dyn Fn() + Send + Sync>) {
    let key = Arc::new(load_key());
    let enigo = Arc::new(Mutex::new(Enigo::new(&Settings::default()).expect("input injection unavailable")));
    watch_front();
    let radio = shared.clone();
    bt::serve(move |s| handle(s, &key, &shared, &enigo, &*show), move |on| radio.lock().unwrap().no_bluetooth = !on);
}

#[cfg(test)]
mod tests {
    use super::{parse_hello, parse_where};

    #[test]
    fn hello_needs_the_whole_header() {
        let mut f = vec![b'H', 2];
        f.extend_from_slice(&[4u8; 65]);
        f.extend_from_slice(&[9u8; 16]);
        f.extend_from_slice("Pixel".as_bytes());
        let (p, n, name) = parse_hello(&f).unwrap();
        assert_eq!((p.len(), n.len(), name.as_str()), (65, 16, "Pixel"));
        assert!(parse_hello(&f[..80]).is_none());
        f[1] = 1; // a phone from before the Bluetooth meeting
        assert!(parse_hello(&f).is_none());
    }

    #[test]
    fn where_the_phone_waits() {
        let (tcp, udp, ips) = parse_where(&[b'W', 0xB0, 0x9D, 0xAA, 0x42, 192, 168, 0, 23, 10, 0, 0, 5]).unwrap();
        assert_eq!((tcp, udp), (45213, 43586));
        assert_eq!(ips, vec![std::net::Ipv4Addr::new(192, 168, 0, 23), std::net::Ipv4Addr::new(10, 0, 0, 5)]);
        assert!(parse_where(&[b'W', 0, 1, 0, 2]).is_none(), "no address");
        assert!(parse_where(&[b'W', 0, 1, 0, 2, 1, 2, 3]).is_none(), "a cut address");
        assert!(parse_where(&[b'X', 0, 1, 0, 2, 192, 168, 0, 23]).is_none());
        assert!(parse_where(&[[b'W', 0, 1, 0, 2].as_slice(), &[1u8; 36]].concat()).is_none(), "more than 8");
    }
}
