//! The tray icon: a phone-and-waves glyph that follows the taskbar's theme, with an ember dot while a phone has
//! control and pause bars while paused. Its menu shows
//! the status and offers Pause or Resume, Open (the window) and Quit. Runs the Windows message loop on the main thread.
use crate::state::Shared;
use crate::ui::strings;
use std::sync::Arc;
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, MouseButton, TrayIconBuilder, TrayIconEvent};

#[derive(Clone, Copy, PartialEq)]
enum Look {
    Idle,
    On,
    Paused,
}

/// Windows' taskbar theme: true when it's light (dark glyph), false when dark (white glyph).
fn light_taskbar() -> bool {
    use windows_sys::Win32::System::Registry::*;
    let key: Vec<u16> = r"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize".encode_utf16().chain([0]).collect();
    let name: Vec<u16> = "SystemUsesLightTheme".encode_utf16().chain([0]).collect();
    let mut value = 0u32;
    let mut size = 4u32;
    let ok = unsafe { RegGetValueW(HKEY_CURRENT_USER, key.as_ptr(), name.as_ptr(), RRF_RT_REG_DWORD, std::ptr::null_mut(), &mut value as *mut u32 as *mut _, &mut size) };
    ok == 0 && value == 1
}

fn icon(look: Look, light: bool) -> Icon {
    let png: &[u8] = match (light, look) {
        (true, Look::Idle) => include_bytes!("../assets/tray-light-idle.png"),
        (true, Look::On) => include_bytes!("../assets/tray-light-on.png"),
        (true, Look::Paused) => include_bytes!("../assets/tray-light-paused.png"),
        (false, Look::Idle) => include_bytes!("../assets/tray-dark-idle.png"),
        (false, Look::On) => include_bytes!("../assets/tray-dark-on.png"),
        (false, Look::Paused) => include_bytes!("../assets/tray-dark-paused.png"),
    };
    let img = image::load_from_memory(png).expect("tray icon").into_rgba8();
    let (w, h) = img.dimensions();
    Icon::from_rgba(img.into_raw(), w, h).expect("tray icon")
}

pub fn run(shared: Shared, show: Arc<dyn Fn() + Send + Sync>) {
    let t = strings();
    let status = MenuItem::new(t.waiting, false, None);
    let pause = MenuItem::new(t.pause, true, None);
    let open = MenuItem::new(t.open, true, None);
    let quit = MenuItem::new(t.quit, true, None);
    let menu = Menu::new();
    let _ = menu.append_items(&[&status, &PredefinedMenuItem::separator(), &pause, &open, &quit]);
    let tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip(t.title)
        .with_icon(icon(Look::Idle, light_taskbar()))
        .build()
        .expect("tray icon");

    let mut shown: Option<(bool, Option<String>, bool)> = None;
    loop {
        // Windows' message loop: the tray icon's clicks and menu arrive through it. A timer wakes it twice a second.
        unsafe {
            use windows_sys::Win32::UI::WindowsAndMessaging::*;
            let mut msg: MSG = std::mem::zeroed();
            SetTimer(0, 1, 500, None);
            if GetMessageW(&mut msg, 0, 0, 0) <= 0 {
                return;
            }
            TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        while let Ok(e) = MenuEvent::receiver().try_recv() {
            if e.id == quit.id() {
                std::process::exit(0);
            } else if e.id == open.id() {
                show();
            } else if e.id == pause.id() {
                let mut st = shared.lock().unwrap();
                st.paused = !st.paused;
                if st.paused {
                    st.end_session(None);
                }
            }
        }
        while let Ok(e) = TrayIconEvent::receiver().try_recv() {
            if let TrayIconEvent::Click { button: MouseButton::Left, .. } = e {
                show();
            }
        }
        let now = {
            let st = shared.lock().unwrap();
            (st.paused, st.connected.clone(), light_taskbar())
        };
        if shown.as_ref() != Some(&now) {
            let (paused, connected, light) = &now;
            let text = match (paused, connected) {
                (true, _) => t.paused_now.to_owned(),
                (_, Some(name)) => format!("{} {name}", t.connected),
                _ => t.waiting.to_owned(),
            };
            status.set_text(&text);
            pause.set_text(if *paused { t.resume } else { t.pause });
            let look = if *paused { Look::Paused } else if connected.is_some() { Look::On } else { Look::Idle };
            let _ = tray.set_icon(Some(icon(look, *light)));
            let _ = tray.set_tooltip(Some(format!("{}: {text}", t.title)));
            shown = Some(now);
        }
    }
}
