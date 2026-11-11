//! Controlled for PC: smooth mode's receiver (docs/plans/pc-bluetooth-rendezvous.md). The phone, paired with this PC,
//! opens its Bluetooth channel (bt.rs), pairs once with a 6-digit code shown on both screens, then this PC dials it
//! over Wi-Fi and input comes sealed (crypto.rs, server.rs). A tray icon shows
//! when a phone has control (tray.rs); the window (ui.rs, its own process) shows status, allowed phones and pause.
// No console window: it runs in the background, started with Windows (MSIX startup task).
#![cfg_attr(not(test), windows_subsystem = "windows")]
mod bt;
mod control;
mod crypto;
mod input;
mod server;
mod state;
mod tray;
mod ui;

use std::net::TcpListener;
use std::process::{Child, Command};
use std::sync::{Arc, Mutex};

/// Windows says how the package was launched: its startup task at sign-in, or the person opening it.
fn started_at_sign_in() -> bool {
    use windows::ApplicationModel::{Activation::ActivationKind, AppInstance};
    AppInstance::GetActivatedEventArgs().and_then(|a| a.Kind()).is_ok_and(|k| k == ActivationKind::StartupTask)
}

fn spawn_window() -> Option<Child> {
    Command::new(std::env::current_exe().ok()?).arg("--window").spawn().ok()
}

fn main() {
    // No console to show a crash: write it to %APPDATA%\Controlled\error.log (inside the package's own folder
    // when installed from the Store), so a failure can be diagnosed.
    std::panic::set_hook(Box::new(|info| {
        let _ = std::fs::write(state::dir().join("error.log"), format!("{info}\n"));
    }));
    if std::env::args().any(|a| a == "--window") {
        return ui::run();
    }
    // The window's local port doubles as the one-copy lock.
    let Ok(control) = TcpListener::bind(("127.0.0.1", control::PORT)) else {
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
    server::start(shared.clone(), show.clone());
    control::start(control, shared.clone());
    // Opened from Start or the Store: show the window (1.0.0 only did on its very first run, and an old marker left
    // by an uninstall kept it in the tray). Started by Windows at sign-in: stay in the tray.
    if !started_at_sign_in() {
        show();
    }
    tray::run(shared, show);
}
