//! What the background program and its window share: allowed phones (on disk), pause, who is connected, a pending
//! pairing. The window is a separate process that reads and changes this over a local-only port (control.rs).
use std::path::{Path, PathBuf};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex, OnceLock};

/// Where this PC keeps its key and allowed phones. Installed from the Store: the package's own folder, which Windows
/// erases on uninstall (the privacy page promises it). A build run by hand, outside a package: %APPDATA%\Controlled.
pub fn dir() -> PathBuf {
    static DIR: OnceLock<PathBuf> = OnceLock::new();
    DIR.get_or_init(|| {
        let old = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(".")).join("Controlled");
        let d = package_folder().unwrap_or_else(|| old.clone());
        let _ = std::fs::create_dir_all(&d);
        if d != old {
            move_into(&old, &d);
        }
        d
    })
    .clone()
}

fn package_folder() -> Option<PathBuf> {
    let p = windows::Storage::ApplicationData::Current().ok()?.LocalFolder().ok()?.Path().ok()?;
    Some(PathBuf::from(p.to_string()))
}

/// 1.0.0 kept its files in %APPDATA%\Controlled, which an uninstall leaves behind (measured 2026-11-10): move them
/// into [new] once, so allowed phones survive the update, then drop the old folder. Files already in [new] win.
fn move_into(old: &Path, new: &Path) {
    let Ok(entries) = std::fs::read_dir(old) else { return };
    for e in entries.flatten() {
        let to = new.join(e.file_name());
        if !to.exists() && std::fs::copy(e.path(), &to).is_err() {
            return; // keep the old folder rather than lose what couldn't be copied
        }
    }
    let _ = std::fs::remove_dir_all(old);
}

/// A pairing waiting for the person: the window shows the name and code with Allow and Deny.
pub struct Ask {
    /// The phone's public key: one question per phone.
    pub key: String,
    pub name: String,
    pub code: String,
    pub answer: Sender<bool>,
}

#[derive(Default)]
pub struct State {
    pub paused: bool,
    /// Bluetooth is off here, or this PC has none (bt.rs keeps trying).
    pub no_bluetooth: bool,
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
            self.connected = None; // the window stops saying "Connected" now, not when the session's thread notices
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

/// One change to the phones file at a time (pairing, renaming and removing run on different threads).
static PHONES: Mutex<()> = Mutex::new(());

/// Writes the whole list to a new file, then puts it in place, so a reader never sees it half-written or empty.
fn save(list: &[(String, String)]) {
    let text: String = list.iter().map(|(k, n)| format!("{k}\t{n}\n")).collect();
    let tmp = phones_path().with_extension("tmp");
    if std::fs::write(&tmp, &text).is_err() {
        return;
    }
    // Another program (an antivirus scan) may hold the file a moment: try again, and at worst write it in place
    // rather than lose an allowed or removed phone.
    for _ in 0..5 {
        if std::fs::rename(&tmp, phones_path()).is_ok() {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
    let _ = std::fs::write(phones_path(), text);
    let _ = std::fs::remove_file(&tmp);
}

pub fn allow(key: &str, name: &str) {
    let _lock = PHONES.lock().unwrap();
    let mut list = phones();
    if list.iter().any(|(k, _)| k == key) {
        return;
    }
    let name = unique(&list, key, name);
    list.push((key.to_owned(), name));
    save(&list);
}

/// A known phone that now gives a friendlier name (phones before 1.19 sent their model code, "2412DPC0AG"): keep it.
pub fn rename(key: &str, name: &str) {
    let _lock = PHONES.lock().unwrap();
    let mut list = phones();
    let Some((_, old)) = list.iter().find(|(k, _)| k == key) else { return };
    let new = unique(&list, key, name);
    if *old == new {
        return;
    }
    for (k, n) in list.iter_mut() {
        if k == key {
            *n = new.clone();
        }
    }
    save(&list);
}

/// [name] for [key] in [list], numbered when another phone already has it: two of the same model, or the dev app on
/// the same phone, read "POCO X7 Pro" and "POCO X7 Pro 2". A name [key] already holds stays as it is.
fn unique(list: &[(String, String)], key: &str, name: &str) -> String {
    let base = name.replace(['\t', '\n'], " ").trim().to_owned();
    let base = if base.is_empty() { "Phone".to_owned() } else { base };
    let taken = |n: &str| list.iter().any(|(k, other)| k != key && other == n);
    let mine = list.iter().find(|(k, _)| k == key).map(|(_, n)| n.as_str());
    (1..).map(|i| if i == 1 { base.clone() } else { format!("{base} {i}") }).find(|n| mine == Some(n.as_str()) || !taken(n)).unwrap()
}

pub fn remove(key: &str) {
    let _lock = PHONES.lock().unwrap();
    let kept: Vec<_> = phones().into_iter().filter(|(k, _)| k != key).collect();
    save(&kept);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn old_files_move_into_the_package_folder_once() {
        let root = std::env::temp_dir().join(format!("controlled-move-{}", std::process::id()));
        let (old, new) = (root.join("old"), root.join("new"));
        std::fs::create_dir_all(&old).unwrap();
        std::fs::create_dir_all(&new).unwrap();
        std::fs::write(old.join("phones"), "k	POCO
").unwrap();
        std::fs::write(old.join("key"), "old").unwrap();
        std::fs::write(new.join("key"), "new").unwrap();
        move_into(&old, &new);
        assert_eq!(std::fs::read_to_string(new.join("phones")).unwrap(), "k	POCO
");
        assert_eq!(std::fs::read_to_string(new.join("key")).unwrap(), "new");
        assert!(!old.exists());
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn same_names_are_numbered_and_kept() {
        let list = vec![("a".to_owned(), "POCO X7 Pro".to_owned()), ("b".to_owned(), "POCO X7 Pro 2".to_owned())];
        assert_eq!(unique(&list, "c", "POCO X7 Pro"), "POCO X7 Pro 3");
        assert_eq!(unique(&list, "b", "POCO X7 Pro"), "POCO X7 Pro 2"); // b keeps its number
        assert_eq!(unique(&list, "a", "POCO X7 Pro"), "POCO X7 Pro");
        assert_eq!(unique(&list, "c", "Pixel 8"), "Pixel 8");
        assert_eq!(unique(&list, "c", " \t"), "Phone");
    }
}
