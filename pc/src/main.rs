//! Controlled for PC: smooth mode's receiver (docs/plans/pc-wifi.md). The phone finds it by mDNS, pairs once with a
//! 6-digit code shown on both screens, then sends input: reliable events over TCP, pointer and scroll over UDP, all
//! sealed (crypto.rs). Only the phone of the current session moves this PC.
mod crypto;

use enigo::{Axis, Button, Coordinate, Direction, Enigo, Keyboard, Mouse, Settings};
use p256::SecretKey;
use std::io::{Read, Write};
use std::net::{IpAddr, TcpListener, TcpStream, UdpSocket};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

const PORT: u16 = 47805;

struct Session {
    phone: IpAddr,
    keys: crypto::Keys,
    last_udp: u64,
}

fn dir() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    let d = base.join("Controlled");
    let _ = std::fs::create_dir_all(&d);
    d
}

fn load_key() -> SecretKey {
    let path = dir().join("key");
    if let Ok(b) = std::fs::read(&path) {
        if let Ok(k) = SecretKey::from_slice(&b) {
            return k;
        }
    }
    let k = SecretKey::random(&mut rand::rngs::OsRng);
    std::fs::write(&path, k.to_bytes()).expect("can't save this PC's key");
    k
}

fn trusted() -> Vec<String> {
    std::fs::read_to_string(dir().join("phones")).unwrap_or_default().lines().map(str::to_owned).collect()
}

fn trust(phone_pub: &str) {
    let mut f = std::fs::OpenOptions::new().create(true).append(true).open(dir().join("phones")).expect("phones file");
    let _ = writeln!(f, "{phone_pub}");
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
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
fn parse_hello(f: &[u8]) -> Option<(&[u8], &[u8], String)> {
    if f.len() < 2 + crypto::PUB_LEN + crypto::NONCE_LEN || f[0] != b'H' || f[1] != 1 {
        return None;
    }
    let pub_end = 2 + crypto::PUB_LEN;
    let nonce_end = pub_end + crypto::NONCE_LEN;
    Some((&f[2..pub_end], &f[pub_end..nonce_end], String::from_utf8_lossy(&f[nonce_end..]).chars().take(40).collect()))
}

/// Applies one input event. Moves and scroll may come over UDP; the rest only over TCP.
fn apply(enigo: &mut Enigo, e: &[u8]) {
    let i16_at = |i: usize| i16::from_le_bytes([e[i], e[i + 1]]) as i32;
    let _ = match (e.first(), e.len()) {
        (Some(b'M'), 5) => enigo.move_mouse(i16_at(1), i16_at(3), Coordinate::Rel),
        (Some(b'S'), 3) => {
            let (v, h) = (e[1] as i8 as i32, e[2] as i8 as i32);
            let _ = if v != 0 { enigo.scroll(-v, Axis::Vertical) } else { Ok(()) };
            if h != 0 { enigo.scroll(h, Axis::Horizontal) } else { Ok(()) }
        }
        (Some(b'B'), 3) => {
            let button = match e[1] { 2 => Button::Right, 3 => Button::Middle, _ => Button::Left };
            enigo.button(button, if e[2] == 1 { Direction::Press } else { Direction::Release })
        }
        (Some(b'T'), _) => enigo.text(&String::from_utf8_lossy(&e[1..])),
        _ => Ok(()),
    };
}

fn handle(mut s: TcpStream, key: &SecretKey, session: &Mutex<Option<Session>>, enigo: &Mutex<Enigo>) {
    let phone_ip = match s.peer_addr() { Ok(a) => a.ip(), Err(_) => return };
    let _ = s.set_read_timeout(Some(Duration::from_secs(90)));
    let Some(hello) = read_frame(&mut s) else { return };
    let Some((phone_pub, phone_nonce, name)) = parse_hello(&hello) else { return };
    let pc_pub = crypto::public_bytes(key);
    let pc_nonce: [u8; crypto::NONCE_LEN] = rand::random();
    let known = trusted().contains(&hex(phone_pub));
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
        let code = crypto::code(phone_pub, &pc_pub, phone_nonce, &pc_nonce);
        let answer = rfd::MessageDialog::new()
            .set_title("Controlled")
            .set_description(format!("Let \"{name}\" control this PC?\n\nThe phone must show the same code:\n\n{code}"))
            .set_buttons(rfd::MessageButtons::YesNo)
            .show();
        if answer != rfd::MessageDialogResult::Yes {
            return;
        }
        trust(&hex(phone_pub));
    }
    if !write_frame(&mut s, &crypto::seal(&keys.to_phone, crypto::TCP, 1, b"OK")) {
        return;
    }
    let _ = s.set_read_timeout(None);
    println!("connected: {name} ({phone_ip})");
    *session.lock().unwrap() = Some(Session { phone: phone_ip, keys: crypto::Keys { to_pc: keys.to_pc.clone(), to_phone: keys.to_phone }, last_udp: 0 });
    let mut last = 0u64;
    while let Some(f) = read_frame(&mut s) {
        let Some((n, e)) = crypto::open(&keys.to_pc, crypto::TCP, &f) else { break };
        if n <= last {
            break; // replayed or reordered on a reliable channel: drop the session
        }
        last = n;
        apply(&mut enigo.lock().unwrap(), &e);
    }
    let mut cur = session.lock().unwrap();
    if cur.as_ref().is_some_and(|c| c.phone == phone_ip) {
        *cur = None;
    }
    println!("disconnected: {name}");
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

fn main() {
    let key = Arc::new(load_key());
    let session: Arc<Mutex<Option<Session>>> = Arc::new(Mutex::new(None));
    let enigo = Arc::new(Mutex::new(Enigo::new(&Settings::default()).expect("input injection unavailable")));
    let _mdns = advertise();
    let udp = UdpSocket::bind(("0.0.0.0", PORT)).expect("UDP port in use");
    {
        let (session, enigo) = (session.clone(), enigo.clone());
        std::thread::spawn(move || {
            let mut buf = [0u8; 512];
            loop {
                let Ok((n, from)) = udp.recv_from(&mut buf) else { continue };
                let mut cur = session.lock().unwrap();
                let Some(s) = cur.as_mut().filter(|s| s.phone == from.ip()) else { continue };
                let Some((counter, e)) = crypto::open(&s.keys.to_pc, crypto::UDP, &buf[..n]) else { continue };
                if counter <= s.last_udp {
                    continue; // late or replayed: the next move carries the motion
                }
                s.last_udp = counter;
                drop(cur);
                if matches!(e.first(), Some(b'M') | Some(b'S')) {
                    apply(&mut enigo.lock().unwrap(), &e);
                }
            }
        });
    }
    let listener = TcpListener::bind(("0.0.0.0", PORT)).expect("TCP port in use");
    println!("Controlled for PC listening on {PORT}");
    for s in listener.incoming().flatten() {
        let (key, session, enigo) = (key.clone(), session.clone(), enigo.clone());
        std::thread::spawn(move || handle(s, &key, &session, &enigo));
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
