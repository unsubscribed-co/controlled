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
