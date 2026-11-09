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
}

#[derive(Default)]
pub struct Input {
    buttons: u8,
    mods: u8,
    keys: Vec<u8>,
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
                    if let Some(key) = key(*k) {
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
                    if let Some(key) = key(*k) {
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

pub fn run(enigo: &mut Enigo, actions: Vec<Action>) {
    for a in actions {
        let _ = match a {
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
            Action::Text(t) => enigo.text(&t),
        };
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
    fn buttons_media_and_text() {
        let mut i = Input::default();
        assert_eq!(i.actions(&[b'B', 1]), vec![Action::Button(1, true)]);
        assert_eq!(i.actions(&[b'B', 0]), vec![Action::Button(1, false)]);
        assert_eq!(i.actions(&[b'C', 0xE9, 0]), vec![Action::Key(Key::VolumeUp, true), Action::Key(Key::VolumeUp, false)]);
        assert_eq!(i.actions(&[b'T', b'h', 0xC3, 0xB1]), vec![Action::Text("hñ".into())]);
        assert_eq!(i.actions(&[b'M', 3, 0, 0xFD, 0xFF]), vec![Action::Move(3, -3)]);
        assert!(i.actions(&[b'X']).is_empty());
    }
}
