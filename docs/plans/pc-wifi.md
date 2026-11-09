# Plan: smooth PC control over Wi-Fi ("Controlled for PC")

Status: waiting for Juan's OK, 2026-11-09.

## Why
- **Classic Bluetooth stutters.** Measured on Juan's desktop: the phone sends a move every 12 ms, but the PC receives them in clumps, with gaps of 60 to 160 ms about 2.6 times a second. On the Legion it's worse. The app can't fix this.
- **BLE ruled out.** Measured smooth: one move every 15 ms, no gap over 45 ms. But whenever the app dies near the PC (an update, a swipe, Xiaomi closing it), Windows forgets the BLE mouse until it's paired again. See branch `spike/ble-mouse`.
- **Wi-Fi with a small PC program** is smooth and doesn't depend on Bluetooth or on the app staying alive. It's distributed through the Microsoft Store: the name **Controlled for PC** is reserved (Store ID 9N08NXM529FH), and the first submission is due by about 2027-02-09.

## What the person sees
1. In the PC controller, which is connected over Bluetooth as today, an **Enable smooth mode** button.
2. The phone, acting as a keyboard, opens the program's Store page on the PC (Win+R, then `ms-windows-store://pdp/?ProductId=9N08NXM529FH`). The person clicks **Install**.
3. The program starts and shows a 6-character code. The phone finds the PC on the Wi-Fi and asks for the code; this is the same screen as Google TV pairing.
4. After that, the PC controller uses Wi-Fi whenever the PC is on the same network, and falls back to Bluetooth by itself when it isn't. A small status line says which one is in use.

## What Wi-Fi improves besides smoothness
- **Exact characters.** Text is sent as Unicode characters, so the PC's keyboard layout no longer matters: no layout question, and no characters that can't be typed.
- **Room for PC → phone clipboard** later. It's not in this plan.

## What Wi-Fi can't do (Bluetooth stays for these)
- **Windows' secure screens:** the sign-in screen, UAC prompts, and windows of programs running as administrator. Windows blocks simulated input from a normal program there. The controller says "Use Bluetooth for this screen" and switches.
- **A PC that's off or asleep:** the program isn't running.

## How it's built
- **PC program:** a small C#/.NET tray app with no window, except the pairing code and a tray icon to quit.
  - It starts with Windows (MSIX startup task).
  - It advertises itself on the local network by mDNS (`_controlled._tcp`).
  - It receives input and replays it with Windows' `SendInput`: relative mouse moves, scroll, buttons, keys by scancode, text by Unicode, and media keys.
- **Transport:**
  - one TLS connection (TCP, no delay) for keys, text and control;
  - UDP for pointer moves and scroll, each packet authenticated with a key from the TLS session, so nobody else on the Wi-Fi can move the mouse;
  - lost moves aren't resent: the next one carries the motion.
- **Pairing:** the same scheme as the Android TV Remote that Controlled already speaks.
  - Each side has its own certificate.
  - The code on the PC screen proves that the phone is talking to that PC.
  - The PC remembers the phone's certificate and refuses any other.
- **Phone:**
  - the PC link gains a Wi-Fi path next to Bluetooth;
  - the PC is found with NsdManager, as Google TVs are now;
  - switching between Wi-Fi and Bluetooth is automatic.
- **Firewall and packaging:** the inbound rule is declared in the MSIX manifest (`desktop2:FirewallRules`), private networks only, so Windows shouldn't ask. This is verified on a real install before the Store submission.
- **Repo:** the PC program lives in `pc/` in this repo (open source, like the app).
- **Tools to install on this PC:** the .NET SDK and the Windows SDK (makeappx, signtool), both from Microsoft through winget.

## Steps (each ends with a measurement)
1. **Prove smoothness first.**
   - Build: a bare program (UDP moves only, no security), and a phone build that sends to it.
   - Measure with `rawmouse.py` on this PC, against the Bluetooth numbers.
   - If it isn't clearly smoother, stop.
2. **Pairing and security:** TLS, the code screen, authenticated UDP, and the remembered phone. Unit tests on both sides with known-answer bytes, like `GtvTest`.
3. **Everything else:**
   - keys, Unicode text, media keys and screen tools over Wi-Fi;
   - automatic switching with Bluetooth;
   - the secure-screen notice.
4. **Install flow:**
   - the Enable smooth mode button;
   - an MSIX built and sideloaded on this PC to check the startup task and the firewall (no prompts);
   - then on the Legion, including Worms, and with the friend's X3.
5. **Store:**
   - listing in EN and ES;
   - a `unsubscribed.dev/controlled/pc` page;
   - privacy page update;
   - age rating;
   - screenshots.
   - Juan submits (his "ship it"). The phone app's matching version ships to Play at the same time.

## Juan's decisions
- This plan.
- Installing the .NET SDK and the Windows SDK on this PC.
- Later, the Store listing text and the submission.
