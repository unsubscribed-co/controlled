//! Turns the phone's input events into this PC's input. The phone sends the same reports its Bluetooth keyboard and mouse
//! send (HID usages), so the PC only diffs each report against the last one: what's newly down is pressed, what's gone
//! is released, and nothing can stay held after a report that lets it go.
use enigo::{Axis, Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse};

#[derive(Debug, PartialEq)]
pub enum Action {
    Move(i32, i32),
    Scroll(i32, i32),
    Button(u8, bool),
    Key(Key, bool),
    Text(String),
    /// Pasted on the phone: whole, by the clipboard and Ctrl+V (phone 1.22+).
    Paste(String),
    /// This many Backspaces (phone 1.22+).
    Erase(u16),
}

#[derive(Default)]
pub struct Input {
    buttons: u8,
    mods: u8,
    keys: Vec<u8>,
    /// What each held key went to the PC as: a translated shortcut ([localized]) can differ, or be nothing.
    sent: Vec<(u8, Option<u8>)>,
    /// The program in front and Windows' language; tests set their own.
    fore: Option<fn() -> (String, u16)>,
}

/// Shortcuts some programs translate: (part of the program's path, Windows' language, modifiers, the key the phone sends,
/// what goes instead; None: nothing). The first row that matches counts; everything else gets the shortcut as sent.
/// - Windows 11's Notepad (from the Store) in Spanish: Ctrl+A opens a file and no shortcut selects all (2026-11-11,
///   Juan's laptop), so the phone's Select all does nothing there rather than open a file.
/// - The classic Notepad (Windows 10) in Spanish: Ctrl+E selects all (2026-11-11, Juan's desktop).
const LOCALIZED: &[(&str, u16, u8, u8, Option<u8>)] = &[
    ("\\microsoft.windowsnotepad_", LANG_SPANISH, CTRL, 0x04, None),
    ("\\notepad.exe", LANG_SPANISH, CTRL, 0x04, Some(0x08)),
];
const LANG_SPANISH: u16 = 0x0A;
const CTRL: u8 = 0x01;

/// What [usage] with [mods] should be in the program at [path] when Windows is in [lang] (a LANGID); None when it isn't
/// an exception.
pub fn localized(path: &str, lang: u16, mods: u8, usage: u8) -> Option<Option<u8>> {
    let mods = (mods | mods >> 4) & 0x0F; // left and right modifiers alike
    let path = path.to_lowercase();
    LOCALIZED
        .iter()
        .find(|(e, l, m, k, _)| path.contains(e) && lang & 0x3FF == *l && mods == *m && usage == *k)
        .map(|r| r.4)
}

/// The program in front (its full path) and Windows' display language.
fn foreground() -> (String, u16) {
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::Globalization::GetUserDefaultUILanguage;
    use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW};
    use windows_sys::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};
    unsafe {
        let lang = GetUserDefaultUILanguage();
        let mut pid = 0;
        GetWindowThreadProcessId(GetForegroundWindow(), &mut pid);
        let p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if p == 0 {
            return (String::new(), lang);
        }
        let mut buf = [0u16; 512];
        let mut len = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(p, PROCESS_NAME_WIN32, buf.as_mut_ptr(), &mut len) != 0;
        CloseHandle(p);
        let path = if ok { String::from_utf16_lossy(&buf[..len as usize]) } else { String::new() };
        (path, lang)
    }
}

const MODS: [(u8, Key); 4] = [(0x11, Key::Control), (0x22, Key::Shift), (0x44, Key::Alt), (0x88, Key::Meta)];

/// A HID keyboard usage as a key on this PC; None for usages the phone never sends.
pub fn key(usage: u8) -> Option<Key> {
    let c = |c: char| Some(Key::Unicode(c));
    match usage {
        0x04..=0x1D => c((b'a' + usage - 0x04) as char),
        0x1E..=0x26 => c((b'1' + usage - 0x1E) as char),
        0x27 => c('0'),
        0x28 => Some(Key::Return),
        0x29 => Some(Key::Escape),
        0x2A => Some(Key::Backspace),
        0x2B => Some(Key::Tab),
        0x2C => Some(Key::Space),
        0x2D => c('-'),
        0x2E => c('='),
        0x2F => c('['),
        0x30 => c(']'),
        0x31 => c('\\'),
        0x33 => c(';'),
        0x34 => c('\''),
        0x35 => c('`'),
        0x36 => c(','),
        0x37 => c('.'),
        0x38 => c('/'),
        0x39 => Some(Key::CapsLock),
        0x3A => Some(Key::F1),
        0x3B => Some(Key::F2),
        0x3C => Some(Key::F3),
        0x3D => Some(Key::F4),
        0x3E => Some(Key::F5),
        0x3F => Some(Key::F6),
        0x40 => Some(Key::F7),
        0x41 => Some(Key::F8),
        0x42 => Some(Key::F9),
        0x43 => Some(Key::F10),
        0x44 => Some(Key::F11),
        0x45 => Some(Key::F12),
        0x4A => Some(Key::Home),
        0x4B => Some(Key::PageUp),
        0x4C => Some(Key::Delete),
        0x4D => Some(Key::End),
        0x4E => Some(Key::PageDown),
        0x4F => Some(Key::RightArrow),
        0x50 => Some(Key::LeftArrow),
        0x51 => Some(Key::DownArrow),
        0x52 => Some(Key::UpArrow),
        _ => None,
    }
}

/// A consumer (media) usage as a key.
pub fn media(usage: u16) -> Option<Key> {
    match usage {
        0xE9 => Some(Key::VolumeUp),
        0xEA => Some(Key::VolumeDown),
        0xE2 => Some(Key::VolumeMute),
        0xCD => Some(Key::MediaPlayPause),
        0xB5 => Some(Key::MediaNextTrack),
        0xB6 => Some(Key::MediaPrevTrack),
        _ => None,
    }
}

impl Input {
    /// The actions one event means, given what's held.
    pub fn actions(&mut self, e: &[u8]) -> Vec<Action> {
        let mut out = Vec::new();
        let i16_at = |i: usize| i16::from_le_bytes([e[i], e[i + 1]]) as i32;
        match (e.first(), e.len()) {
            (Some(b'M'), 5) => out.push(Action::Move(i16_at(1), i16_at(3))),
            (Some(b'S'), 3) => out.push(Action::Scroll(e[1] as i8 as i32, e[2] as i8 as i32)),
            (Some(b'B'), 2) => {
                for bit in [1u8, 2, 4] {
                    if (self.buttons ^ e[1]) & bit != 0 {
                        out.push(Action::Button(bit, e[1] & bit != 0));
                    }
                }
                self.buttons = e[1];
            }
            // A keyboard report: modifiers, reserved, six key slots.
            (Some(b'R'), 9) => {
                let (mods, keys) = (e[1], e[3..9].iter().copied().filter(|&k| k != 0).collect::<Vec<_>>());
                for k in self.keys.iter().filter(|k| !keys.contains(k)) {
                    // Let go of what this key went in as.
                    let as_sent = self.sent.iter().position(|(s, _)| s == k).map_or(Some(*k), |i| self.sent.remove(i).1);
                    if let Some(key) = as_sent.and_then(key) {
                        out.push(Action::Key(key, false));
                    }
                }
                for (bits, key) in MODS {
                    let (was, is) = (self.mods & bits != 0, mods & bits != 0);
                    if was != is {
                        out.push(Action::Key(key, is));
                    }
                }
                for k in keys.iter().filter(|k| !self.keys.contains(k)) {
                    // A shortcut (a modifier held) may be one the program in front translates.
                    let to = if mods & 0x0F | mods >> 4 & 0x0F != 0 {
                        let (exe, lang) = self.fore.unwrap_or(foreground)();
                        localized(&exe, lang, mods, *k).unwrap_or(Some(*k))
                    } else {
                        Some(*k)
                    };
                    self.sent.push((*k, to));
                    if let Some(key) = to.and_then(key) {
                        out.push(Action::Key(key, true));
                    }
                }
                self.mods = mods;
                self.keys = keys;
            }
            (Some(b'C'), 3) => {
                if let Some(key) = media(u16::from_le_bytes([e[1], e[2]])) {
                    out.push(Action::Key(key, true));
                    out.push(Action::Key(key, false));
                }
            }
            (Some(b'T'), _) => out.push(Action::Text(String::from_utf8_lossy(&e[1..]).into_owned())),
            (Some(b'P'), _) => out.push(Action::Paste(String::from_utf8_lossy(&e[1..]).into_owned())),
            (Some(b'D'), 3) => out.push(Action::Erase(u16::from_be_bytes([e[1], e[2]]))),
            _ => {}
        }
        out
    }

    /// Lets go of everything held: the phone left mid-press.
    pub fn release_all(&mut self) -> Vec<Action> {
        let mut out = self.actions(&[b'R', 0, 0, 0, 0, 0, 0, 0, 0]);
        out.extend(self.actions(&[b'B', 0]));
        out
    }
}

/// Diagnostics: with a file named `trace` in this program's folder, every key, button and text it types goes to
/// `input.log` with the time and what Windows said. Off by default; pointer moves are never logged.
fn trace(line: &str) {
    use std::io::Write;
    static ON: std::sync::OnceLock<bool> = std::sync::OnceLock::new();
    if !*ON.get_or_init(|| crate::state::dir().join("trace").exists()) {
        return;
    }
    let ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis());
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(crate::state::dir().join("input.log")) {
        let _ = writeln!(f, "{ms} {line}");
    }
}

/// The gap between typed characters, and between Backspaces of one erase.
const TYPE_GAP_MS: u64 = 15;
const ERASE_GAP_MS: u64 = 3;
/// How long a pasted text stays on the clipboard for the app to read it. shortcut: an app that takes longer to read it
/// pastes what was there before; raise it if one is seen doing so.
const PASTE_HOLD_MS: u64 = 600;
/// When the last character was typed, so the next keeps [TYPE_GAP_MS] whichever text it comes in.
static LAST_TYPED: std::sync::Mutex<Option<std::time::Instant>> = std::sync::Mutex::new(None);

pub fn run(enigo: &mut Enigo, actions: Vec<Action>) {
    for a in actions {
        let logged = !matches!(a, Action::Move(..) | Action::Scroll(..));
        // Never the text itself (it may be a password): only how long it is.
        let what = match &a {
            _ if !logged => String::new(),
            Action::Text(t) => format!("Text({} chars)", t.chars().count()),
            Action::Paste(t) => format!("Paste({} chars)", t.chars().count()),
            a => format!("{a:?}"),
        };
        let result = match a {
            Action::Move(dx, dy) => enigo.move_mouse(dx, dy, Coordinate::Rel),
            Action::Scroll(v, h) => {
                let _ = if v != 0 { enigo.scroll(-v, Axis::Vertical) } else { Ok(()) };
                if h != 0 { enigo.scroll(h, Axis::Horizontal) } else { Ok(()) }
            }
            Action::Button(bit, down) => {
                let b = match bit { 2 => Button::Right, 4 => Button::Middle, _ => Button::Left };
                enigo.button(b, if down { Direction::Press } else { Direction::Release })
            }
            Action::Key(k, down) => enigo.key(k, if down { Direction::Press } else { Direction::Release }),
            // A character at a time, a little apart: 2026-11-11, a laptop on Windows 11 lost and repeated characters
            // when 464 came in 3.7 s (Windows 10 typed them exact). Typing by hand is far slower than this.
            // Also between texts: a phone sends each typed letter as its own, and 36 that came 7 ms apart came out garbled
            // on the same laptop (2026-11-11).
            Action::Text(t) => t.chars().try_for_each(|c| {
                let mut last = LAST_TYPED.lock().unwrap();
                let gap = std::time::Duration::from_millis(TYPE_GAP_MS);
                if let Some(wait) = last.and_then(|l| gap.checked_sub(l.elapsed())) {
                    std::thread::sleep(wait);
                }
                *last = Some(std::time::Instant::now());
                enigo.text(c.encode_utf8(&mut [0; 4]))
            }),
            Action::Paste(t) => {
                let before = crate::clip::text();
                if crate::clip::put(&t) {
                    let ours = crate::clip::changes();
                    // The V key itself (not the letter, which a Russian or Greek layout lacks), and Ctrl always let go.
                    let pressed = enigo.key(Key::Control, Direction::Press).and_then(|_| enigo.key(Key::Other(0x56), Direction::Click));
                    let released = enigo.key(Key::Control, Direction::Release);
                    // The app reads the clipboard when it gets to the Ctrl+V: give it that, then put back what was there
                    // (unless something else was copied meanwhile). Also keeps the pieces of a long paste in order.
                    std::thread::sleep(std::time::Duration::from_millis(PASTE_HOLD_MS));
                    if crate::clip::changes() == ours {
                        match before {
                            Some(b) => {
                                crate::clip::put(&b);
                            }
                            None => crate::clip::clear(),
                        }
                    }
                    pressed.and(released)
                } else {
                    enigo.text(&t) // the clipboard was busy: type it rather than lose it
                }
            }
            Action::Erase(n) => (0..n).try_for_each(|_| {
                std::thread::sleep(std::time::Duration::from_millis(ERASE_GAP_MS));
                enigo.key(Key::Backspace, Direction::Click)
            }),
        };
        if logged {
            trace(&format!("{what} -> {result:?}"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn keyboard_reports_press_and_release_by_diff() {
        let mut i = Input::default();
        // Ctrl down with C, then all released.
        assert_eq!(i.actions(&[b'R', 0x01, 0, 0x06, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Control, true), Action::Key(Key::Unicode('c'), true)]);
        assert_eq!(i.actions(&[b'R', 0, 0, 0, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Unicode('c'), false), Action::Key(Key::Control, false)]);
        // Alt held across two Tab presses (window switching), then let go.
        assert_eq!(i.actions(&[b'R', 0x04, 0, 0x2B, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Alt, true), Action::Key(Key::Tab, true)]);
        assert_eq!(i.actions(&[b'R', 0x04, 0, 0, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Tab, false)]);
        assert_eq!(i.release_all(), vec![Action::Key(Key::Alt, false)]);
    }

    #[test]
    fn a_translated_shortcut_follows_the_table_and_lets_go_cleanly() {
        let notepad_es: fn() -> (String, u16) =
            || (r"C:\Program Files\WindowsApps\Microsoft.WindowsNotepad_11.2508.38.0_x64__8wekyb3d8bbwe\Notepad\Notepad.exe".into(), 0x0C0A);
        let mut i = Input { fore: Some(notepad_es), ..Default::default() };
        let (ctrl, a) = (0x01u8, 0x04u8);
        // Spanish Notepad on Windows 11: Select all (Ctrl+A) presses nothing but Ctrl, so no file opens.
        assert_eq!(i.actions(&[b'R', ctrl, 0, 0, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Control, true)]);
        assert_eq!(i.actions(&[b'R', ctrl, 0, a, 0, 0, 0, 0, 0]), vec![]);
        assert_eq!(i.actions(&[b'R', ctrl, 0, 0, 0, 0, 0, 0, 0]), vec![]);
        assert_eq!(i.actions(&[b'R', 0, 0, 0, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Control, false)]);
        // The classic Notepad (Windows 10) in Spanish: Ctrl+E, and its release lets go of the E.
        let classic: fn() -> (String, u16) = || (r"C:\Windows\System32\notepad.exe".into(), 0x0C0A);
        let mut c = Input { fore: Some(classic), ..Default::default() };
        c.actions(&[b'R', ctrl, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(c.actions(&[b'R', ctrl, 0, a, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Unicode('e'), true)]);
        assert_eq!(c.actions(&[b'R', ctrl, 0, 0, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Unicode('e'), false)]);
        // Elsewhere, or typed without Ctrl, it's the key as sent.
        let chrome: fn() -> (String, u16) = || (r"C:\Program Files\Google\Chrome\Application\chrome.exe".into(), 0x0C0A);
        let mut j = Input { fore: Some(chrome), ..Default::default() };
        j.actions(&[b'R', ctrl, 0, 0, 0, 0, 0, 0, 0]);
        assert_eq!(j.actions(&[b'R', ctrl, 0, a, 0, 0, 0, 0, 0]), vec![Action::Key(Key::Unicode('a'), true)]);
        assert_eq!(localized(r"C:\Windows\System32\notepad.exe", 0x0409, ctrl, a), None, "English Notepad keeps Ctrl+A");
        assert_eq!(localized(r"C:\Windows\System32\notepad.exe", 0x0C0A, 0, a), None, "a plain a is a letter");
    }

    #[test]
    fn buttons_media_and_text() {
        let mut i = Input::default();
        assert_eq!(i.actions(&[b'B', 1]), vec![Action::Button(1, true)]);
        assert_eq!(i.actions(&[b'B', 0]), vec![Action::Button(1, false)]);
        assert_eq!(i.actions(&[b'C', 0xE9, 0]), vec![Action::Key(Key::VolumeUp, true), Action::Key(Key::VolumeUp, false)]);
        assert_eq!(i.actions(&[b'T', b'h', 0xC3, 0xB1]), vec![Action::Text("hñ".into())]);
        assert_eq!(i.actions(&[b'P', b'a', b'/', b'?']), vec![Action::Paste("a/?".into())]);
        assert_eq!(i.actions(&[b'D', 1, 0xC8]), vec![Action::Erase(456)]);
        assert_eq!(i.actions(&[b'D', 1]), vec![], "a cut erase does nothing");
        assert_eq!(i.actions(&[b'M', 3, 0, 0xFD, 0xFF]), vec![Action::Move(3, -3)]);
        assert!(i.actions(&[b'X']).is_empty());
    }
}
