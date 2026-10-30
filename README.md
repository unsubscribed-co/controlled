# Controlled

One remote for the devices at home, on your phone. From [Unsubscribed!](https://unsubscribed.dev): no ads, no subscriptions, no account, open source (AGPL-3.0).

**Free, forever.** Controlled runs entirely on your phone and costs us nothing to run, so we will never charge for it. If we ever add something that does cost money to run, like an AI feature on a server, only that part could be paid: at cost, plus a 100% fee: of every dollar, 50¢ covers what it costs to run and 50¢ keeps the project going. Everything Controlled does today stays free.

[Get it on Google Play](https://unsubscribed.dev/controlled/) · [Privacy](https://unsubscribed.dev/controlled/privacy/)

## What it controls today

**LG smart TVs (webOS)**, over your home Wi-Fi:
- a big d-pad, volume and channels that repeat while held, play and pause;
- the TV's apps with their own icons (pin favourites, most used first), inputs, and live typing into the TV's search;
- a touchpad for the TV's pointer;
- turn the TV off, and on again over Wi-Fi, Bluetooth, or the phone's IR blaster;
- share a YouTube or Netflix link from the phone to play it on the TV;
- the phone's volume keys work as the TV's.

**Windows PCs**, over Bluetooth, with nothing installed on the PC:
- the phone becomes a keyboard and trackpad;
- one-tap tools for windows, tabs, editing and system: switch and close windows, Task Manager, lock, and more;
- typing in Latin American Spanish, Spain Spanish or US layout.

Add as many TVs and PCs as you have, pick a default, and switch from the name at the top. English and Spanish, dark and light.

More devices are coming.

## Private by design

Controlled talks only to your own devices, over your home Wi-Fi or Bluetooth. There is no server of ours, no account and no analytics. The TV's certificate is pinned on first pairing and checked every time.

## Build it yourself

Android Studio, or `./gradlew assembleDevDebug` (JDK 25). Then install `app/build/outputs/apk/dev/debug/app-dev-debug.apk`.

Signing, releases and the Play build: [docs/CONFIG.md](docs/CONFIG.md).

## How it works

- **TV:** LG's SSAP protocol over a WebSocket on `wss://<tv>:3001`, found with SSDP. Protocol details follow [aiowebostv](https://github.com/home-assistant-libs/aiowebostv) (Apache-2.0). How power-on works, measured: [docs/research](docs/research).
- **PC:** Android's Bluetooth HID device profile. The phone registers as a keyboard, mouse and media keys, so Windows needs no driver (`PcHid.kt`, `Keymap.kt`).

## About Unsubscribed!

We build useful open-source apps. When an app costs us nothing to run, it's free forever. When it does (servers, AI), we run it for you at what it costs us, plus a 100% fee: half covers the running cost, half keeps the project going. No subscriptions, no ads, and your data is never sold. You can always take the code and run it yourself. [unsubscribed.dev](https://unsubscribed.dev)

## License

[AGPL-3.0](LICENSE).
