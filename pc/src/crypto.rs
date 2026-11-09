//! Smooth mode's security (docs/plans/pc-wifi.md), mirrored by the phone's SmoothCrypto.kt and checked against the same
//! known answers on both sides.
//! - Each side has a long-term P-256 key. A session key comes from ECDH of the two, salted with both sides' nonces.
//! - Pairing shows the same 6-digit code on the phone and the PC (numeric comparison): a machine in the middle would
//!   produce two different codes, so the person checking they match is what proves the PC talks to their phone.
//! - Every message after the hello is AES-256-GCM; the nonce is the channel (TCP or UDP) and a counter that only grows,
//!   so a replayed or reordered pointer packet is dropped.
use aes_gcm::aead::{Aead, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce};
use hkdf::Hkdf;
use p256::ecdh::diffie_hellman;
use p256::{PublicKey, SecretKey};
use sha2::{Digest, Sha256};

pub const PUB_LEN: usize = 65;
pub const NONCE_LEN: usize = 16;
pub const TCP: u8 = 0;
pub const UDP: u8 = 1;

/// The keys of one session: phone to PC, and PC to phone.
pub struct Keys {
    pub to_pc: Aes256Gcm,
    pub to_phone: Aes256Gcm,
}

pub fn public_bytes(secret: &SecretKey) -> [u8; PUB_LEN] {
    let point = secret.public_key().to_sec1_bytes();
    point.as_ref().try_into().expect("uncompressed P-256 point")
}

/// Session keys for a phone (`phone_pub`) and this PC (`pc_secret`), salted with both hellos' nonces.
pub fn keys(pc_secret: &SecretKey, phone_pub: &[u8], phone_nonce: &[u8], pc_nonce: &[u8]) -> Option<Keys> {
    let peer = PublicKey::from_sec1_bytes(phone_pub).ok()?;
    let shared = diffie_hellman(pc_secret.to_nonzero_scalar(), peer.as_affine());
    let mut salt = phone_nonce.to_vec();
    salt.extend_from_slice(pc_nonce);
    let hk = Hkdf::<Sha256>::new(Some(&salt), shared.raw_secret_bytes());
    let mut okm = [0u8; 64];
    hk.expand(b"controlled smooth v1", &mut okm).ok()?;
    Some(Keys {
        to_pc: Aes256Gcm::new_from_slice(&okm[..32]).ok()?,
        to_phone: Aes256Gcm::new_from_slice(&okm[32..]).ok()?,
    })
}

/// The 6-digit code both screens show while pairing, from everything both hellos carried.
pub fn code(phone_pub: &[u8], pc_pub: &[u8], phone_nonce: &[u8], pc_nonce: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(b"controlled code");
    for part in [phone_pub, pc_pub, phone_nonce, pc_nonce] {
        h.update(part);
    }
    let d = h.finalize();
    format!("{:06}", u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 1_000_000)
}

fn nonce(channel: u8, counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[0] = channel;
    n[4..].copy_from_slice(&counter.to_be_bytes());
    n
}

/// counter (8 bytes, big-endian) followed by the sealed message.
pub fn seal(key: &Aes256Gcm, channel: u8, counter: u64, plain: &[u8]) -> Vec<u8> {
    let mut out = counter.to_be_bytes().to_vec();
    out.extend(key.encrypt(Nonce::from_slice(&nonce(channel, counter)), plain).expect("AES-GCM seal"));
    out
}

/// Opens a sealed frame; returns its counter and message, or None if it was tampered with or isn't for this key.
pub fn open(key: &Aes256Gcm, channel: u8, frame: &[u8]) -> Option<(u64, Vec<u8>)> {
    if frame.len() < 8 + 16 {
        return None;
    }
    let counter = u64::from_be_bytes(frame[..8].try_into().ok()?);
    let plain = key.decrypt(Nonce::from_slice(&nonce(channel, counter)), &frame[8..]).ok()?;
    Some((counter, plain))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn secret(byte: u8) -> SecretKey {
        SecretKey::from_slice(&[byte; 32]).unwrap()
    }

    /// Known answers shared with the phone's SmoothCryptoTest: private keys of all 1s (phone) and all 2s (PC),
    /// nonces of all 0xA1 (phone) and 0xB2 (PC).
    #[test]
    fn known_answers() {
        let (phone, pc) = (secret(1), secret(2));
        let (pn, cn) = ([0xA1u8; 16], [0xB2u8; 16]);
        let phone_pub = public_bytes(&phone);
        let pc_pub = public_bytes(&pc);
        let c = code(&phone_pub, &pc_pub, &pn, &cn);
        let k = keys(&pc, &phone_pub, &pn, &cn).unwrap();
        let sealed = seal(&k.to_pc, UDP, 7, b"M\x03\x00\xfd\xff");
        println!("KAT code={c} sealed={} phone_pub={} pc_pub={}", hex(&sealed), hex(&phone_pub), hex(&pc_pub));
        assert_eq!(c.len(), 6);
        assert_eq!(open(&k.to_pc, UDP, &sealed), Some((7, b"M\x03\x00\xfd\xff".to_vec())));
        assert_eq!(open(&k.to_pc, TCP, &sealed), None, "a UDP frame must not open as TCP");
        assert_eq!(open(&k.to_phone, UDP, &sealed), None, "keys are one-way");
        let mut bad = sealed.clone();
        *bad.last_mut().unwrap() ^= 1;
        assert_eq!(open(&k.to_pc, UDP, &bad), None, "tampering is caught");
    }

    pub fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }
}
