//! Controlled for PC: smooth mode's receiver (docs/plans/pc-bluetooth-rendezvous.md). The phone, paired with this PC,
//! opens its Bluetooth channel (bt.rs), pairs once with a 6-digit code shown on both screens, then this PC dials it
//! over Wi-Fi and input comes sealed (crypto.rs, server.rs). A tray icon shows
//! when a phone has control (tray.rs); the window (ui.rs, its own process) shows status, allowed phones and pause.
// No console window: it runs in the background, started with Windows (MSIX startup task).
#![cfg_attr(not(test), windows_subsystem = "windows")]
mod bt;
mod clip;
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

/// Brings the window to the front if it's open (restored if minimized); false when there's none.
fn focus_window() -> bool {
    use windows_sys::Win32::UI::WindowsAndMessaging::{FindWindowW, IsIconic, SW_RESTORE, SetForegroundWindow, ShowWindow};
    let title: Vec<u16> = ui::TITLE.encode_utf16().chain([0]).collect();
    unsafe {
        let w = FindWindowW(std::ptr::null(), title.as_ptr());
        if w == 0 {
            return false;
        }
        if IsIconic(w) != 0 {
            ShowWindow(w, SW_RESTORE);
        }
        SetForegroundWindow(w);
        true
    }
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
        // Already running (started with Windows, then opened from the Start menu): its window comes to the front, or the
        // running copy opens it, so there's never a second one (2026-11-11: each open added a window).
        if !focus_window() {
            control::send("show");
        }
        return;
    };
    let shared: state::Shared = Default::default();
    // One window at a time: Open, a click on the tray icon or a pairing to allow all bring up the same one.
    let window: Arc<Mutex<Option<Child>>> = Default::default();
    let show: Arc<dyn Fn() + Send + Sync> = Arc::new(move || {
        let mut w = window.lock().unwrap();
        if w.as_mut().is_some_and(|c| matches!(c.try_wait(), Ok(None))) {
            focus_window(); // already open: to the front, not a second one
            return;
        }
        *w = spawn_window();
    });
    server::start(shared.clone(), show.clone());
    control::start(control, shared.clone(), show.clone());
    // Opened from Start or the Store: show the window (1.0.0 only did on its very first run, and an old marker left
    // by an uninstall kept it in the tray). Started by Windows at sign-in: stay in the tray.
    if !started_at_sign_in() {
        show();
    }
    tray::run(shared, show);
}
