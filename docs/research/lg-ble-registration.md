# Registering a Bluetooth waker on an LG webOS TV, without ThinQ

> **Result on hardware (2026-10-26, dev.14 to dev.16, Juan's 70UP7500PSC): blocked.** `asc/registerDevice` answers
> `401 insufficient permissions` to Controlled. Same after a fresh pairing (Forget, Accept on the TV) with an unsigned
> manifest that also asked for every permission name found in ThinQ's code (`CONTROL_BLUETOOTH`, `CONTROL_WAKEUP`,
> `CONTROL_DEVICE`, `CONTROL_DEVICE_VOLUME`, `CONTROL_TV_POWER`, `CONTROL_SETTINGS_OVERRIDE`, `READ_DEVICE`,
> `READ_DEVICE_CONFIG`, `READ_DEVICE_FLAG`, `READ_DEVICE_INFO`, `READ_NETWORK_INTERFACE_FLAG`, `READ_TV_PROGRAM_INFO`).
> Most likely only an LG-signed manifest gets it; we don't reuse ThinQ's signature. So the wake key stays a pasted
> setting (⋮ > Bluetooth wake key). The code draft is kept in `git stash` ("ble registration draft").
>
> Confirmed along the way: `Registration.wakeKey(0a:1b:2c:3d:4e:5f)` rebuilds ThinQ's captured key exactly, so the
> key is ThinQ's random MAC + `07 00` (the header byte is 07 on air, not the 08 the decompiled code suggests).

Follows `lg-power-on.md` (how the wake advertisement is built). This answers: how does ThinQ enrol an allowed waker, and
can Controlled do the same? **Verdict: yes, and it needs no Bluetooth at all — one SSAP call over Wi-Fi.** Evidence is
from decompiling ThinQ (`com.lgeha.nuts`) with `dexdump`; addresses are `dexdump -d` offsets in the dumps under
`C:\Users\Juan\AppData\Local\Temp\thinq-apk\`.

## The surprise: wake enrolment is SSAP, not GATT

`ConnectSdkHandler.registrationBLEWakeup` (c19 `480de4`) does not pair over GATT. It:
1. reads this phone's BLE identity, `NetworkManager.getAddress(BT)` (c19 `480e66`);
2. calls `wifiWakeupManager.registerDevice(controlHandler, connectableDevice, address)` (c19 `480e7a`) — the enrolment;
3. stores the TV's own Bluetooth MAC as the device property `registered_bt_address` (c19 `480eae`).

`wifiWakeupManager.registerDevice` (c19 `4bbd10`) builds a JSON body and sends one SSAP request:

```
uri:     ssap://com.webos.service.asc/registerDevice      (c19 4bbd84)
payload: { "type": "ThinQ", "address": "<phone BLE MAC>" } (c19 4bbd54 type, 4bbd62 address)
```

Over the already-connected webOS WebSocket (`requestSSG` → ConnectableDevice). Success is `result = true` in the
response. There is **no GATT connection, no PIN, no on-screen prompt, no key exchange** for wake enrolment.

So the TV keeps an allow-list of waker MACs. It later powers on from deep sleep for a BLE advertisement whose
manufacturer data (company `0x00C4`, service `0xFEBA`) carries a registered MAC — see `lg-power-on.md`.

## Where the GATT / pairing classes fit (not needed for wake)

The LECCP GATT service does exist and is used for *initial device onboarding / Wi-Fi provisioning*, not for wake:
- Service `UUID_LECCP_SERVICE` = `0000feb9-…`, control `UUID_LECCP_CONTROL` = `0000feba-…` (c15 `5b8600`).
- Characteristics (all `00002abX-0000-1000-8000-00805f9b34fb`, c15 `5b76a8`+): ACTION `2ab8`, MODE `2abf`,
  PAIR_CODES `2abd`, PAIR_KEY_DATA `2abe`, P2P `2ab3`, WIFI_AP_INFO `2aba`.
- `LBSManager$ActionRequest.sendAction` (c15 `596ea0`) writes `UUID_LECCP_ACTION`: actionId 11 = SET_WAKE_UP, 12 =
  SET_PAIRING.
- `RegisterData` "encryption" is plain repeating-key **XOR** (`encryptXOR`, c15 `5b7cdc`; `setEncryptKey`, `5b7de8`) —
  no real crypto — so even this path would be replicable if ever needed. The `setPin`/`PIN_CODE` strings belong to the
  **SSAP** client-key pairing (c15 `423d4a` `ssap://pairing/setPin`), which Controlled already performs.

We do not need any of this: the wake allow-list is managed purely by the `asc/registerDevice` SSAP call.

## What Controlled does

`Registration.kt` + `RemoteModel.registerBleWake()`:
1. Reuse the MAC already in the wake key if present, else generate a random **locally-administered unicast** MAC
   (`Registration.randomWakeMac`, matching ThinQ's `getOrCreateRandomizedMacAddress` → `createRandomUnicastAddress`).
2. While connected (TV on), `request("com.webos.service.asc/registerDevice", {type:"ThinQ", address:<mac>})`.
3. On success, save the wake key = `mac(12 hex) + "0800"` (address + control header `byteSize 0x08`, `controlId 0x00`),
   which the existing `BleWake.send` advertises as `key + TV address`.

Result: Turn on wakes the TV from deep sleep using Controlled's own registered identity — ThinQ never required. The
menu action is ⋮ → "Register for Bluetooth wake"; it needs the TV on and connected.

## Open risks to confirm on hardware
- **SSAP permission**: `com.webos.service.asc/registerDevice` may need a permission Controlled's pairing manifest
  doesn't request; the call could return an error for a non-ThinQ client. If so, widen the register manifest. (Untested.)
- **Address format**: we register lowercase `aa:bb:…`; the TV should key on the parsed bytes, but confirm the advert's
  6 bytes match what was registered.
- The `07` vs `08` header-byte discrepancy from `lg-power-on.md` still stands; a fresh capture would settle it.

## GATT wake-enrol (connected path) — 2026-10-26

The SSAP enrol above is signature-gated (401). ThinQ has a SECOND wake-enrol path over the connected GATT LECCP link,
in its TV registration helper `com.lge.lms.things.service.seamless.SeamlessRegisterHelper`, and it is **consent-based
(PIN on the TV), not signature-gated**. Addresses are `dexdump -d` offsets under `C:\Users\Juan\AppData\Local\Temp\thinq-apk\`.

Flow (seamless register):
1. **Connect** GATT to the TV; service `UUID_LECCP_SERVICE 0000feb9-...`, chars (`00002abX-...`) ACTION `2ab8`, MODE `2abf`.
2. **readMode** (c19 `455f68`): read `UUID_LECCP_MODE 2abf` → a mode string ("firstuse_data_mode"/"DEFAULT"). Plaintext.
3. **Pair** (user consent):
   - `actionSubPairingPair` (c19 `45599c`): `SET_PAIRING` (actionId **12**, sub **1**), actionData carries the phone's
     device name → **the TV shows a PIN on screen**. (The exact sub=1 actionData byte layout — a 0x01/0x02 wrapper around
     the name — could not be reconstructed byte-exact from the disassembly; register reuse obscures it.)
   - `actionSubPairingSend` (c19 `455bb4`): `SET_PAIRING` (12, sub **2**), actionData = `PairingData.getBytes`
     (c15 `5b7bcc`) = `[mPairCode=2][pin ascii]`. Wrong PIN → `PINCODE_ERROR`.
4. **SET_WAKE_UP** (`actionWakeup`, c19 `455da0`): Action actionId **11**, sub **1**, **no actionData**. Response is
   `WakeUp.create` (c15 `5b7538`) = a 1-byte result only (no key/address returned).

Transport: `LBSManager$ActionRequest.sendAction` (c15 `596ea0`) writes `Request.getBytes` (c15 `5b7394` =
`[actionId][subActionId][actionData]`) to `UUID_LECCP_ACTION`. The register/Wi-Fi-sync step (not needed for plain wake)
is XOR-"encrypted": `RegisterData.encryptXOR` (c15 `5b7cdc`, trivial repeating-key XOR) with key
`LmsModelUtil.makeKey(deviceId, UUID)` (c15 `597044`) — **derived from the TV's device id + a constant UUID, no secret**.
Pairing and SET_WAKE_UP themselves are plaintext.

**Derivability verdict: PASS (no LG signature, no server secret).** The XOR key is `makeKey(deviceId, UUID)` (known
inputs); the PIN is user-entered; the frames are plain `[id][sub][data]`. Nothing requires LG's app signature — unlike
the SSAP path.

**The open question the live probe must answer:** `SET_WAKE_UP` carries no MAC and returns no key, so the enrolled waker
identity is NOT set here. It is either (a) the GATT peer address of the connection (which we can't choose, and can't
reproduce in an Android advertisement → our random-MAC advert wouldn't match → dead end), or (b) the self-assigned
random MAC conveyed in a preceding register/Wi-Fi-sync step (c19 `455852`/`4558ae` send `NetworkManager.getAddress(BT)`),
which our `BleWake` advert already uses → works. Plus: whether the TV even exposes the FEB9 LECCP service to an
unpaired connection at all. The dev probe (below) answers these before we write the full pairing client.

### Dev experiment shipped (GattWake.kt, dev build only)
`GattWake.probe(tvMac)` connects to the TV's BLE address (= Wi-Fi MAC + 1, like `BleWake`), discovers and logs every
service/characteristic, reads MODE, and enables+logs notifications on ACTION. `GattWake.write(hex)` writes a raw frame
to ACTION on the open connection. Confirmed framing helpers `SET_WAKE_UP` and `pairPin(pin)` have unit tests
(`GattWakeTest`). It deliberately does **not** implement the pairing state machine (the sub=1 actionData and the
notify mechanism aren't byte-exact yet) — build that only after the probe shows FEB9 is reachable and how responses arrive.

Triggers (dev app, over adb):
- `am start -n <dev app>/<activity> --es gatt_probe 1` — connect + dump services (watch `adb logcat -s Controlled`).
- `am start ... --es gatt_write 0b01` — send SET_WAKE_UP on the open connection; `--es gatt_write 0c02<pinhex>` for PIN.

## Live hardware result (2026-10-26, dev.17–dev.22, Juan's 70UP7500PSC): GATT path is a dead end for self-enrol

Ran the probe + a full pairing attempt against the TV over Wi-Fi ADB. Findings, in order:

- **FEB9 is reachable by our unsigned app** — but only while the TV is fully awake. In standby it advertises FEB9 (from
  a random address, name `[LG] webOS TV UP7500PSC`, LG mfr data that rolls per advert) yet refuses GATT connects
  (30 s timeout, `status=147`). Must scan for the advert and connect to the advertised address; the public identity
  (`a4:ce:da:61:d0:d5`) is not directly connectable.
- **Characteristics read:** MODE (`2abf`) = ASCII `DEFAULT`; `2ab9` = the TV's own identity MAC `a6:ce:da:61:d0:d4`
  (Wi-Fi MAC with the locally-administered bit set); `2abb` = a 21-byte device blob; PAIR_CODES (`2abd`) = `0205`;
  PAIR_KEY_DATA (`2abe`) = empty.
- **Pairing WORKS, consent-based, no LG signature.** `SET_PAIRING` sub=1 frame is `0c 01` + `02` + device-name ASCII +
  `0000` (the `BleData.NULL` 2-byte terminator). On that frame the TV showed **"Controlled está intentando conectarse"**
  with an 8-digit PIN and replied notify `01`. Sending the PIN back as `0c 02` + `02` + PIN ASCII (`SET_PAIRING` sub=2)
  returned notify `03` and the prompt cleared — paired. `SET_WAKE_UP` (`0b 01`) returns notify `00`.
- **But `SET_WAKE_UP` does NOT bind an advertisable MAC.** It takes no MAC (appending one is ignored, still `00`). Wake
  tests (TV off ~45 s, pure BLE advert, no WoL): our chosen MAC `02:11:22:33:44:55` → TV stayed off; the phone's own
  identity MAC `9c:9e:d5:75:71:86` → stayed off; **control** with ThinQ's registered MAC `0a1b2c3d4e5f0700` → TV woke in
  ~6 s. So it binds the bonded connection's rotating private address, which a connectionless advert can't carry.

**Conclusion:** the deep-sleep wake allow-list is written only by the SSAP `asc/registerDevice` call (first-party,
`401` for us). GATT pairing is open to any unsigned nearby app but does not populate that list, so a third-party app
cannot self-enrol an advertisable waker. ThinQ is required once (after a TV reset) to set the wake MAC; Controlled then
pastes it. Experiment code is on branch `experiment/ble-gatt-wake`.

**Security note (Rapid7):** the FEB9/LECCP GATT service is reachable by any unsigned nearby app while the TV is awake,
and accepts PIN-consent pairing plus `SET_PAIRING`/`SET_WAKE_UP`/`SET_WIFI_SYNC` with plaintext (at most XOR) frames —
a broad adjacent surface, though not itself a wake bypass. The wake advert remains forgeable/replayable (see `lg-power-on.md`).
