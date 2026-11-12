//! The window: a separate process (`controlled-pc --window`) showing ui.html in the system WebView, talking to the
//! background program through control.rs, so closing it never stops smooth mode. The page asks for the status every
//! half second and sends the person's actions as control commands; `open <url>` opens settings or the web.
use crate::control::{self, Status};
use tao::event::{Event, WindowEvent};
use tao::event_loop::{ControlFlow, EventLoopBuilder};
use tao::window::WindowBuilder;
use wry::WebViewBuilder;

/// The tray menu's texts (the window has its own, in ui.html).
/// The window's title, the same in every language: how a second copy finds it to bring it to the front.
pub const TITLE: &str = "Controlled for PC";

pub struct T {
    pub title: &'static str,
    pub connected: &'static str,
    pub waiting: &'static str,
    pub paused_now: &'static str,
    pub pause: &'static str,
    pub resume: &'static str,
    pub quit: &'static str,
    pub open: &'static str,
}

pub fn strings() -> &'static T {
    let es = sys_locale::get_locale().is_some_and(|l| l.starts_with("es"));
    if es { &ES } else { &EN }
}

const EN: T = T {
    title: TITLE,
    connected: "Connected to",
    waiting: "Ready, waiting for your phone",
    paused_now: "Smooth mode paused",
    pause: "Pause smooth mode",
    resume: "Turn smooth mode back on",
    quit: "Quit",
    open: "Open Controlled for PC",
};

const ES: T = T {
    title: TITLE,
    connected: "Conectado a",
    waiting: "Listo, esperando tu celular",
    paused_now: "Modo fluido en pausa",
    pause: "Pausar el modo fluido",
    resume: "Reactivar el modo fluido",
    quit: "Salir",
    open: "Abrir Controlled for PC",
};

fn json_str(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out += "\\\"",
            '\\' => out += "\\\\",
            c if (c as u32) < 0x20 => out += &format!("\\u{:04x}", c as u32),
            c => out.push(c),
        }
    }
    out + "\""
}

/// The status as the page's render() takes it; null when the background program isn't running.
pub fn json(s: &Option<Status>) -> String {
    let Some(s) = s else { return "null".into() };
    let phones: Vec<String> = s.phones.iter().map(|(k, n)| format!("[{},{}]", json_str(k), json_str(n))).collect();
    format!(
        "{{\"paused\":{},\"noBluetooth\":{},\"connected\":{},\"ask\":{},\"phones\":[{}],\"version\":{}}}",
        s.paused,
        s.no_bluetooth,
        s.connected.as_deref().map(json_str).unwrap_or("null".into()),
        s.ask.as_ref().map(|(c, n)| format!("[{},{}]", json_str(c), json_str(n))).unwrap_or("null".into()),
        phones.join(","),
        json_str(env!("CARGO_PKG_VERSION"))
    )
}

fn window_icon() -> Option<tao::window::Icon> {
    let img = image::load_from_memory(include_bytes!("../msix/Assets/Square44x44Logo.png")).ok()?.into_rgba8();
    let (w, h) = img.dimensions();
    tao::window::Icon::from_rgba(img.into_raw(), w, h).ok()
}

pub fn run() {
    let event_loop = EventLoopBuilder::<String>::with_user_event().build();
    let window = WindowBuilder::new()
        .with_title(strings().title)
        .with_inner_size(tao::dpi::LogicalSize::new(440.0, 560.0))
        .with_min_inner_size(tao::dpi::LogicalSize::new(360.0, 420.0))
        .with_window_icon(window_icon())
        .build(&event_loop)
        .expect("window");
    let proxy = event_loop.create_proxy();
    let webview = WebViewBuilder::new(&window)
        .with_html(include_str!("ui.html").replace("{{LOGO}}", include_str!("logo.b64").trim()).replace("{{LANG}}", &std::env::var("CONTROLLED_LANG").unwrap_or_default()).replace("{{THEME}}", &std::env::var("CONTROLLED_THEME").unwrap_or_default()))
        .with_ipc_handler(move |cmd: String| {
            let _ = proxy.send_event(cmd);
        })
        .build()
        .expect("webview");
    event_loop.run(move |event, _, flow| {
        *flow = ControlFlow::Wait;
        match event {
            Event::UserEvent(cmd) => {
                if let Some(url) = cmd.strip_prefix("open ") {
                    if url.starts_with("https://unsubscribed.dev/") || url == "ms-settings:startupapps" {
                        let _ = std::process::Command::new("explorer").arg(url).spawn();
                    }
                    return;
                }
                let status = control::send(&cmd);
                let _ = webview.evaluate_script(&format!("render({})", json(&status)));
            }
            Event::WindowEvent { event: WindowEvent::CloseRequested, .. } => *flow = ControlFlow::Exit,
            _ => {}
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_json_escapes_names() {
        let s = Status { paused: false, no_bluetooth: false, connected: Some("Ana's \"POCO\"".into()), ask: None, phones: vec![("ab".into(), "x\\y".into())] };
        let j = json(&Some(s));
        assert!(j.contains(r#""connected":"Ana's \"POCO\"""#));
        assert!(j.contains(r#"["ab","x\\y"]"#));
        assert_eq!(json(&None), "null");
    }
}
