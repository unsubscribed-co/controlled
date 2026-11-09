//! What the background program and its window share: allowed phones (on disk), pause, who is connected, a pending
//! pairing. The window is a separate process that reads and changes this over a local-only port (control.rs).
use std::io::Write;
use std::path::PathBuf;
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};

pub fn dir() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    let d = base.join("Controlled");
    let _ = std::fs::create_dir_all(&d);
    d
}

/// A pairing waiting for the person: the window shows the name and code with Allow and Deny.
pub struct Ask {
    pub name: String,
    pub code: String,
    pub answer: Sender<bool>,
}

#[derive(Default)]
pub struct State {
    pub paused: bool,
    /// The phone sending input right now.
    pub connected: Option<String>,
    pub ask: Option<Ask>,
    /// The current session's socket and phone key, to end it (paused, or its phone removed).
    pub session: Option<(std::net::TcpStream, String)>,
}

impl State {
    /// Ends the current session if it's [key]'s, or any session when None; the phone falls back to Bluetooth.
    pub fn end_session(&mut self, key: Option<&str>) {
        if self.session.as_ref().is_some_and(|(_, k)| key.is_none_or(|key| key == k)) {
            if let Some((s, _)) = self.session.take() {
                let _ = s.shutdown(std::net::Shutdown::Both);
            }
        }
    }
}

pub type Shared = Arc<Mutex<State>>;

/// Allowed phones: one "public key (hex)<TAB>name" per line.
fn phones_path() -> PathBuf {
    dir().join("phones")
}

pub fn phones() -> Vec<(String, String)> {
    std::fs::read_to_string(phones_path())
        .unwrap_or_default()
        .lines()
        .filter_map(|l| {
            let mut p = l.splitn(2, '\t');
            let key = p.next()?.trim();
            (!key.is_empty()).then(|| (key.to_owned(), p.next().unwrap_or("").to_owned()))
        })
        .collect()
}

pub fn is_allowed(key: &str) -> bool {
    phones().iter().any(|(k, _)| k == key)
}

pub fn allow(key: &str, name: &str) {
    if is_allowed(key) {
        return;
    }
    let mut f = std::fs::OpenOptions::new().create(true).append(true).open(phones_path()).expect("phones file");
    let _ = writeln!(f, "{key}\t{}", name.replace(['\t', '\n'], " "));
}

pub fn remove(key: &str) {
    let kept: String = phones().into_iter().filter(|(k, _)| k != key).map(|(k, n)| format!("{k}\t{n}\n")).collect();
    let _ = std::fs::write(phones_path(), kept);
}
