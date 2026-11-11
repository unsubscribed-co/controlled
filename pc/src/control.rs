//! The local channel between the background program and its window (a separate process): one command per connection,
//! on 127.0.0.1 only. Commands: `status`, `pause 1|0`, `remove <key>`, `answer 1|0 <code>`.
use crate::state::{self, Shared};
use std::io::{BufRead, BufReader, Write};
use std::net::{TcpListener, TcpStream};

pub const PORT: u16 = 47806;

#[derive(Default, Clone, PartialEq, Debug)]
pub struct Status {
    pub paused: bool,
    /// This PC's Bluetooth is off (or it has none): phones can't reach it.
    pub no_bluetooth: bool,
    pub connected: Option<String>,
    /// A pairing waiting: (code, phone name).
    pub ask: Option<(String, String)>,
    /// Allowed phones: (key, name).
    pub phones: Vec<(String, String)>,
}

fn reply(shared: &Shared, line: &str) -> String {
    let (cmd, arg) = line.trim().split_once(' ').unwrap_or((line.trim(), ""));
    let mut st = shared.lock().unwrap();
    match cmd {
        "pause" => {
            st.paused = arg == "1";
            if st.paused {
                st.end_session(None);
            }
        }
        "remove" => {
            state::remove(arg);
            st.end_session(Some(arg));
        }
        "answer" => {
            // "answer 1 <code>": only the question the window showed; a newer one that took its place waits.
            let (yes, code) = arg.split_once(' ').unwrap_or((arg, ""));
            if st.ask.as_ref().is_some_and(|a| a.code == code) {
                let _ = st.ask.take().map(|a| a.answer.send(yes == "1"));
            }
        }
        _ => {}
    }
    let mut out = format!("paused {}\nnobt {}\n", st.paused as u8, st.no_bluetooth as u8);
    if let Some(c) = &st.connected {
        out += &format!("connected {c}\n");
    }
    if let Some(a) = &st.ask {
        out += &format!("ask {} {}\n", a.code, a.name);
    }
    for (k, n) in state::phones() {
        out += &format!("phone {k} {n}\n");
    }
    out
}

pub fn parse(text: &str) -> Status {
    let mut s = Status::default();
    for line in text.lines() {
        let (cmd, rest) = line.split_once(' ').unwrap_or((line, ""));
        match cmd {
            "paused" => s.paused = rest == "1",
            "nobt" => s.no_bluetooth = rest == "1",
            "connected" => s.connected = Some(rest.to_owned()),
            "ask" => {
                let (code, name) = rest.split_once(' ').unwrap_or((rest, ""));
                s.ask = Some((code.to_owned(), name.to_owned()));
            }
            "phone" => {
                let (k, n) = rest.split_once(' ').unwrap_or((rest, ""));
                s.phones.push((k.to_owned(), n.to_owned()));
            }
            _ => {}
        }
    }
    s
}

pub fn start(listener: TcpListener, shared: Shared) {
    std::thread::spawn(move || {
        for mut s in listener.incoming().flatten() {
            let mut line = String::new();
            if BufReader::new(&s).read_line(&mut line).is_ok() {
                let _ = s.write_all(reply(&shared, &line).as_bytes());
            }
        }
    });
}

/// From the window: sends one command and returns the background program's status, or None if it isn't running.
pub fn send(cmd: &str) -> Option<Status> {
    let mut s = TcpStream::connect(("127.0.0.1", PORT)).ok()?;
    s.write_all(format!("{cmd}\n").as_bytes()).ok()?;
    let _ = s.shutdown(std::net::Shutdown::Write);
    let mut text = String::new();
    std::io::Read::read_to_string(&mut s, &mut text).ok()?;
    Some(parse(&text))
}

#[cfg(test)]
mod tests {
    use super::{parse, reply};
    use crate::state::{Ask, Shared};

    #[test]
    fn allow_answers_only_the_question_it_was_shown_for() {
        let shared: Shared = Default::default();
        let (tx, rx) = std::sync::mpsc::channel();
        shared.lock().unwrap().ask = Some(Ask { key: "k".into(), name: "Pixel".into(), code: "123456".into(), answer: tx });
        reply(&shared, "answer 1 654321"); // a newer question took its place on the window
        assert!(shared.lock().unwrap().ask.is_some());
        reply(&shared, "answer 1 123456");
        assert!(shared.lock().unwrap().ask.is_none());
        assert_eq!(rx.try_recv(), Ok(true));
    }

    #[test]
    fn parses_status() {
        let s = parse("paused 0\nnobt 1\nconnected POCO X7 Pro\nask 123456 Pixel 9\nphone ab12 POCO X7 Pro\n");
        assert!(!s.paused);
        assert!(s.no_bluetooth);
        assert_eq!(s.connected.as_deref(), Some("POCO X7 Pro"));
        assert_eq!(s.ask, Some(("123456".into(), "Pixel 9".into())));
        assert_eq!(s.phones, vec![("ab12".into(), "POCO X7 Pro".into())]);
    }
}
