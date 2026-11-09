//! Controlled for PC, step 1 prototype (docs/plans/pc-wifi.md): receives pointer moves over UDP and replays them.
//! No pairing or security yet: run it only to measure smoothness, then close it.
//! Packet: b'M', then dx and dy as little-endian i16.
use enigo::{Coordinate, Enigo, Mouse, Settings};
use std::net::UdpSocket;

const PORT: u16 = 47805;

fn decode(p: &[u8]) -> Option<(i32, i32)> {
    if p.len() != 5 || p[0] != b'M' {
        return None;
    }
    Some((i16::from_le_bytes([p[1], p[2]]) as i32, i16::from_le_bytes([p[3], p[4]]) as i32))
}

fn main() {
    let socket = UdpSocket::bind(("0.0.0.0", PORT)).expect("UDP port in use");
    let mut enigo = Enigo::new(&Settings::default()).expect("input injection unavailable");
    println!("listening on UDP {PORT}");
    let mut buf = [0u8; 64];
    loop {
        let Ok((n, _)) = socket.recv_from(&mut buf) else { continue };
        if let Some((dx, dy)) = decode(&buf[..n]) {
            let _ = enigo.move_mouse(dx, dy, Coordinate::Rel);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::decode;

    #[test]
    fn decodes_moves_and_rejects_junk() {
        assert_eq!(decode(&[b'M', 3, 0, 0xFD, 0xFF]), Some((3, -3)));
        assert_eq!(decode(&[b'K', 3, 0, 0, 0]), None);
        assert_eq!(decode(&[b'M', 3, 0]), None);
    }
}
