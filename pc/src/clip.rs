//! Puts text on Windows' clipboard for a paste (Ctrl+V), marked so it stays out of the clipboard history (Win+V) and
//! the cloud clipboard, and [paste] puts back what was there right after: a password pasted from the phone isn't kept
//! on this PC, and what the person had copied isn't lost.
use windows_sys::Win32::Foundation::{GlobalFree, HGLOBAL};
use windows_sys::Win32::System::DataExchange::{CloseClipboard, EmptyClipboard, GetClipboardData, GetClipboardSequenceNumber, IsClipboardFormatAvailable, OpenClipboard, RegisterClipboardFormatW, SetClipboardData};
use windows_sys::Win32::System::Memory::{GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalUnlock};

const CF_UNICODETEXT: u32 = 13;

/// A movable global block holding [bytes], for SetClipboardData (which owns it once set).
unsafe fn global(bytes: &[u8]) -> Option<HGLOBAL> {
    unsafe {
        let h = GlobalAlloc(GMEM_MOVEABLE, bytes.len().max(1));
        if h.is_null() {
            return None;
        }
        let p = GlobalLock(h) as *mut u8;
        if p.is_null() {
            GlobalFree(h);
            return None;
        }
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), p, bytes.len());
        GlobalUnlock(h);
        Some(h)
    }
}

unsafe fn set(format: u32, bytes: &[u8]) -> bool {
    unsafe {
        let Some(h) = global(bytes) else { return false };
        if SetClipboardData(format, h as isize) == 0 {
            GlobalFree(h);
            return false;
        }
        true
    }
}

fn format(name: &str) -> u32 {
    let w: Vec<u16> = name.encode_utf16().chain([0]).collect();
    unsafe { RegisterClipboardFormatW(w.as_ptr()) }
}

/// [text] on the clipboard, out of history and the cloud. False if the clipboard couldn't be had (another program
/// holding it): tries a few times first.
pub fn put(text: &str) -> bool {
    let utf16: Vec<u8> = text.encode_utf16().chain([0]).flat_map(|u| u.to_le_bytes()).collect();
    for _ in 0..10 {
        unsafe {
            if OpenClipboard(0) == 0 {
                std::thread::sleep(std::time::Duration::from_millis(20));
                continue;
            }
            EmptyClipboard();
            let ok = set(CF_UNICODETEXT, &utf16);
            // Windows' own markers (documented for clipboard history): leave this out of Win+V and the cloud.
            set(format("ExcludeClipboardContentFromMonitorProcessing"), &[0]);
            set(format("CanIncludeInClipboardHistory"), &0u32.to_le_bytes());
            set(format("CanUploadToCloudClipboard"), &0u32.to_le_bytes());
            CloseClipboard();
            return ok;
        }
    }
    false
}

/// Windows' count of clipboard changes: the same number means nobody put anything there since.
pub fn changes() -> u32 {
    unsafe { GetClipboardSequenceNumber() }
}

/// The clipboard's text, if it holds text. None also when it couldn't be had.
pub fn text() -> Option<String> {
    unsafe {
        if OpenClipboard(0) == 0 {
            return None;
        }
        let h = GetClipboardData(CF_UNICODETEXT);
        let p = if h == 0 { std::ptr::null_mut() } else { GlobalLock(h as HGLOBAL) as *const u16 };
        let text = (!p.is_null()).then(|| {
            let n = (0..).take_while(|&i| *p.add(i) != 0).count();
            let t = String::from_utf16_lossy(std::slice::from_raw_parts(p, n));
            GlobalUnlock(h as HGLOBAL);
            t
        });
        CloseClipboard();
        text
    }
}

/// Text the person copied, for the phone: not what a password manager or this program's own paste marked as
/// private (Windows' ExcludeClipboardContentFromMonitorProcessing), not empty, and small enough for one frame.
pub fn copied() -> Option<String> {
    // shortcut: longer copies aren't sent (one frame is 64 KB); send them in pieces if anyone copies more.
    const MAX: usize = 60_000;
    if unsafe { IsClipboardFormatAvailable(format("ExcludeClipboardContentFromMonitorProcessing")) } != 0 {
        return None;
    }
    text().filter(|t| !t.is_empty() && t.len() <= MAX)
}

/// Empties the clipboard.
pub fn clear() {
    unsafe {
        if OpenClipboard(0) != 0 {
            EmptyClipboard();
            CloseClipboard();
        }
    }
}
