# TV power-on: what we know, and where the code is (agent handoff)

Context for Controlled (an LG webOS remote). The hard problem is **turning the TV on**, especially from deep sleep.
This file is the index; the detail is in the siblings cited below. Hardware: LG 70UP7500PSC (2021, webOS 6), IP
192.168.1.12, Wi-Fi MAC `A4:CE:DA:61:D0:D4`, BT addr `A4:CE:DA:61:D0:D5` (= Wi-Fi MAC + 1). Phone: POCO X7 Pro,
has an IR blaster.

## The four wake paths, ranked by how deep a sleep they reach

1. **IR blaster** (`IrWake.kt`, shipped 1.4) — universal, any sleep, any brand, **no network/pairing/registration**.
   Cost: line of sight (aim the phone). Only on phones with an emitter (`hasIrEmitter()`), which most lack.
   **Verified**: LG NEC discrete power-on `0x20DF23DC` at 38 kHz woke this TV (~6 s). See `ir-power-on.md`.
2. **BLE wake advert** (`BleWake.kt`, shipped 1.2/1.3) — reaches the TV from any sleep over Bluetooth, hands-off (no
   aiming). Needs an 8-byte "wake key" pasted in ⋮ → Bluetooth wake key. See `lg-power-on.md`.
3. **Wake-on-LAN** (`WakeOnLan.kt`) — works only ~1 h after turn-off; then the TV leaves Wi-Fi. Sent to the TV's IP and
   broadcast.
4. **SSAP `system/turnOn` / `turnOnScreen`** — only while the TV is in standby with its socket still open.

`RemoteModel.powerOn()` fires them together: `bleWake()` (if a key is set) then `irWake()` (only if **no** key and the
phone has an emitter — BLE is preferred because it's hands-off), then WoL + SSAP.

## The BLE wake key — solved (see `lg-power-on.md`)

The advert's manufacturer data (company `0x00C4`, service `0xFEBA`) is `[phone's self-assigned MAC ×6][len 0x08][ctlId
0x00][TV BT addr ×6]`. The "key" is **ThinQ's own randomized, persisted MAC** (`BtNetwork.getAddress()` →
`getOrCreateRandomizedMacAddress`, stored in ThinQ's SettingsDb), registered on the TV via SSAP at pairing. Not a
secret, not cloud, not derived — stable per ThinQ install, changes only on ThinQ data-clear/reinstall. The TV wakes for
an advert carrying a registered MAC. Juan's key: `0a1b2c3d4e5f0700`. (Capture today needs ThinQ + a btsnoop; an in-app
BLE-scan capture is designed but NOT built — see "ideas" below.)

## Self-registering a waker without ThinQ — hit a hard wall (see `lg-ble-registration.md`)

- **SSAP `com.webos.service.asc/registerDevice`** (how ThinQ sets the waker MAC) is **first-party-gated**: `401
  insufficient permissions` even after a fresh pairing requesting all 15 of ThinQ's permission names. Only an
  LG-signed app gets it. We will NOT forge LG's signature.
- **GATT LECCP path** (`com.lge.lms`, service `0xFEB9`): fully reverse-engineered and **tested live**. An unsigned app
  CAN connect and **pair by PIN consent** (TV shows a code; `SET_PAIRING` sub1 = `0c 01 02 <name> 00 00`, sub2 = `0c 02
  02 <pin-ascii>`), and `SET_WAKE_UP` (`0b 01`) is accepted. BUT `SET_WAKE_UP` binds the **bonded connection's rotating
  address**, not a MAC we can advertise — wake tests with our chosen MAC and the phone's identity MAC both failed; the
  ThinQ MAC woke it. So GATT does **not** let a third-party app register an advertisable waker.
- Conclusion: a third-party app cannot self-enrol a BLE waker. ThinQ is needed once after a TV reset; Controlled then
  pastes the captured key. IR sidesteps all of this.

## Repo / release state (as of 2026-10-26)

- `main` = **1.4** (`3f2148a`, pushed): IR power-on merged. 1.3 (`3664735`) shipped the BLE wake key to prod.
- `experiment/ble-gatt-wake` (pushed): the GATT dead-end — `GattWake.kt` probe/scan/pair client + dev triggers. Dead
  end, kept for reference. Also `git stash` entries hold an earlier SSAP-registerDevice draft.
- `experiment/ir-wake` (pushed): the IR feature before merge.
- Dev triggers (gated by `BuildConfig.LOG`, dev flavor only): `--es ble_wake <hex> [--es tv_mac <wifimac>]`,
  `--es ir_power on|toggle|<hex>`, and on the gatt branch `--es gatt_scan|gatt_probe|gatt_read|gatt_write`.
- **1.4 is NOT shipped to Play** (Juan said hold). Signed AAB built at
  `app/build/outputs/bundle/prodRelease/app-prod-release.aab`. Play distribution is **Internal testing** only
  (Production is inactive); upload via Play Console → Controlled → Internal testing → new release. 1.3 was shipped this
  way. Release signing uses the upload keystore in `local.properties`; build with `bundleProdRelease`.
- Decompiled APKs (untrusted, in `%TEMP%`): `thinq-apk/` (com.lgeha.nuts), `controlla-apk/` (com.controlla.lgremoteapp =
  WoL+SSAP only, no IR), `tvremote-apk/` (com.controlla.rokuremoteapp "TV Remote" = IR + WoL + SSAP + Roku; IR codes in
  `assets/encryptedIRCodes/`, encrypted). dexdump = Android build-tools 36.0.0.

## Device state
- Phone on Wi-Fi adb `192.168.1.4:5555` (may drop). Dev app `dev.unsubscribed.controlled.dev` = `dev.24-ir`, **no**
  paired TV / no BLE key (wiped during testing). Prod app `dev.unsubscribed.controlled` = 1.2/1.3, **untouched**,
  holds Juan's pairing; paste `0a1b2c3d4e5f0700` into its Bluetooth wake key menu when 1.3+ lands.
- The dev app is GATT-paired to the TV from testing (harmless).

## Open ideas (not built)
- **In-app BLE key capture**: Controlled scans (`BluetoothLeScanner`) for ThinQ's `0xFEBA` advert whose last 6 bytes
  match the paired TV, extracts the first 6 bytes + `0800` → saves the key. One-tap setup, no adb/btsnoop. Reuses the
  scan code from `GattWake.scanAndProbe`. Needs `BLUETOOTH_SCAN`. Do reactively.
- Ship 1.4 to Play (held by Juan).
