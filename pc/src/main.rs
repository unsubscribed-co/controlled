//! Controlled for PC: smooth mode's receiver (docs/plans/pc-wifi.md). The phone finds it by mDNS, pairs once with a
//! 6-digit code shown on both screens, then sends input over Wi-Fi, sealed (crypto.rs, server.rs). A tray icon shows
//! when a phone has control (tray.rs); the window (ui.rs, its own process) shows status, allowed phones and pause.
// No console window: it runs in the background, started with Windows (MSIX startup task).
#![cfg_attr(not(test), windows_subsystem = "windows")]
mod control;
mod crypto;
mod input;
mod server;
mod state;
mod tray;
mod ui;

use std::net::{TcpListener, UdpSocket};
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};

fn spawn_window() -> Option<Child> {
    Command::new(std::env::current_exe().ok()?).arg("--window").spawn().ok()
}

fn main() {
    if std::env::args().any(|a| a == "--window") {
        return ui::run();
    }
    let (Ok(udp), Ok(listener)) = (UdpSocket::bind(("0.0.0.0", server::PORT)), TcpListener::bind(("0.0.0.0", server::PORT))) else {
        // Already running (started with Windows, then opened from the Start menu): open its window instead.
        spawn_window();
        return;
    };
    let shared: state::Shared = Default::default();
    // One window at a time: Open, a click on the tray icon or a pairing to allow all bring up the same one.
    let window: Arc<Mutex<Option<Child>>> = Default::default();
    let show: Arc<dyn Fn() + Send + Sync> = Arc::new(move || {
        let mut w = window.lock().unwrap();
        if w.as_mut().is_some_and(|c| matches!(c.try_wait(), Ok(None))) {
            return;
        }
        *w = spawn_window();
    });
    server::start(udp, listener, shared.clone(), show.clone());
    control::start(shared.clone());
    let welcomed = state::dir().join("welcomed");
    if !welcomed.exists() {
        let _ = std::fs::write(&welcomed, b"");
        show();
    }
    tray::run(shared, show);
}
