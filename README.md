# Controlled

A phone remote for your TV, from [Unsubscribed!](https://unsubscribed.dev): no ads, no subscriptions, no account, open source (AGPL-3.0).

The phone talks to the TV over your home Wi-Fi, so nothing runs in the cloud and nothing costs money to run. v1 supports LG webOS TVs: d-pad, volume (also on the phone's volume keys), channels, play/pause, power off and on (Wake-on-LAN), the TV's apps and inputs, a keyboard and a touchpad. English and Spanish.

## Build

Android Studio, or `./gradlew assembleDevDebug` (JDK 25). Details, signing and releases: [docs/CONFIG.md](docs/CONFIG.md).

## How it talks to the TV

LG's SSAP protocol over a WebSocket on `wss://<tv>:3001`, found with SSDP. The TV's self-signed certificate is pinned on first pairing. Protocol details follow [aiowebostv](https://github.com/home-assistant-libs/aiowebostv) (Apache-2.0).
