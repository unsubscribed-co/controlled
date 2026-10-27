# Turning an LG webOS TV on: what works, measured

Juan's TV: LG 70UP7500PSC (2021, webOS 6), on Wi-Fi, IP 192.168.1.12, Wi-Fi MAC `A4:CE:DA:61:D0:D4`, wired MAC
`B0:37:95:6D:1D:5C`. Phone: POCO X7 Pro (HyperOS, Android 16). Settings: "Turn on via Wi-Fi" and "Turn on via
Bluetooth" on; Quick Start+ apparently off (on turn-off the TV goes Active → Active Standby → Suspend and closes its
socket).

> The Bluetooth wake key in this document is a made-up example; the real one stays on the owner's phone and is not published.

## Wake-on-LAN (Wi-Fi) works only for about an hour

- **Broadcast magic packets never reach the sleeping TV** (2026-10-22/23): Juan's router drops them, even minutes
  after turn-off.
- **A magic packet sent straight to the TV's IP wakes it** while it still answers ARP: 1.9 s from the PC, 4.3 s to a
  ready session from the phone, with the TV off for 2 minutes. Controlled 1.2 sends to the IP and both broadcasts.
- **About 1 h 10 min after turn-off the TV's Wi-Fi goes fully to sleep** (2026-10-26). A probe every 10 minutes, with
  no wake packets, saw the TV answer ARP at 10:43 and 10:53 and stop at 11:03. It had been off since about 9:45.
- **After that no Wi-Fi wake works**, even with a static ARP entry on the PC: the unicast packet went out, and the TV
  stayed off. The TV most likely leaves the Wi-Fi network.
- A webOS 6 guide agrees that Quick Start+ blocks Wake-on-LAN and "TV On with Mobile" must stay on:
  https://community.home-assistant.io/t/fix-for-wol-wake-on-lan-or-power-on-for-lg-webos-6-tvs/346409
- Within the hour, if the TV sits in standby with its socket still open, SSAP `system/turnOn` (aiowebostv's
  `power_on`) or `com.webos.service.tvpower/power/turnOnScreen` ("Screen Off") turns it on.

## Bluetooth LE: what LG ThinQ sends

LG ThinQ woke the TV from deep sleep (2026-10-26 11:29, after it had stopped answering ARP at 11:03), but only from
ThinQ's device list. Inside the TV's own control screen, the power button was greyed out. The phone's HCI snoop log
(Developer options › Bluetooth HCI snoop log, read from `adb bugreport` at `FS/data/misc/bluetooth/logs/btsnoop_hci.log`)
shows **one 10-second BLE advertisement, with no connection and no data exchanged**:

```
LE Set Ext Adv Params  handle 2, properties 0x0013 (connectable, scannable, legacy PDU), interval 0xA0 (100 ms)
LE Set Ext Scan Rsp    0d 09 "POCO X7 Pro"                  (the phone's name)
LE Set Ext Adv Data    02 01 02                             flags: LE General Discoverable
                       11 ff c4 00                          manufacturer specific, company 0x00C4 = LG Electronics
                             0a 1b 2c 3d 4e 5f 07 00        8 bytes, meaning unknown (see samples)
                             a4 ce da 61 d0 d5              the TV's Bluetooth address (Wi-Fi MAC + 1)
                       03 03 ba fe                          16-bit service UUID 0xFEBA
LE Set Ext Adv Enable  on, then off 10 s later
```

### Samples of the unknown 8 bytes

| When | Bytes | TV state |
|---|---|---|
| 2026-10-26 11:29:04 | `0a 1b 2c 3d 4e 5f 07 00` | deep sleep (no ARP for 26 min) |
| 2026-10-26 11:43:36 | `0a 1b 2c 3d 4e 5f 07 00` | light sleep (off 6 min); TV on 6 s after the tap |

**The bytes don't change between wakes (same phone, same TV):** it isn't a rolling code. (Resolved below by
decompiling ThinQ: the 8 bytes are not a key — they are ThinQ's self-assigned random MAC plus a 2-byte header.)

ThinQ only sends the advertisement right after the app is (re)opened. Later taps on the device list's power button,
greyed out by then, send nothing, and the TV stays off (11:38:24). That explains "the power button is often
unavailable".

If they repeat, it's a fixed prefix and an app can send the same advertisement with Android's `BluetoothLeAdvertiser`.
If they change, it's a rolling code that has to be worked out first.

## Replaying it from Controlled (2026-10-26, dev.12, `BleWake.kt`, triggered over adb)

Each test turned the TV off, waited 40 s (light sleep), then had Controlled send only the BLE advertisement, with no
Wake-on-LAN:

| Test | 8 bytes | Result |
|---|---|---|
| A (off with ThinQ) | ThinQ's `0a1b2c3d4e5f0700` | **TV on about 5 s after the advertisement** |
| B (off with Controlled's power key) | `0000000000000000` | stayed off (48 s) |
| Control (same setup as B) | ThinQ's `0a1b2c3d4e5f0700` | **TV on about 9 s after** |

**The TV checks the 8 bytes.** The advertisement Android builds (legacy, connectable, scannable, 100 ms, high TX
power, manufacturer 0x00C4 with the 8 bytes + the TV's BT address, service 0xFEBA) is enough. ThinQ's scan response
(the phone's name) isn't needed.

## The TV's own advertisement

The TV advertises from a random address (`4c:ae:ee:33:cb:e7` in this log):

```
02 01 1a                        flags
0a ff c4 00 03 34 15 13 17 fd 80  LG manufacturer data
02 0a d4                        TX power
03 03 b9 fe                     service UUID 0xFEB9 (the wake uses 0xFEBA)
08 1b 00 d5 d0 61 da ce a4      LE Bluetooth Device Address: public a4:ce:da:61:d0:d5 (confirms Wi-Fi MAC + 1)
```

The 8 wake bytes aren't in it. (Earlier guess — "a key shared when ThinQ registered the TV" — was half right: the
*TV's* address is from registration, but the leading bytes are the phone's self-assigned identity. See below.)

## Where the 8 bytes come from: solved by decompiling ThinQ (2026-10-26)

Decompiled LG ThinQ (`com.lgeha.nuts`, pulled over adb, disassembled with Android `dexdump`). The wake advertisement
is built by `com.lge.lms.things.service.smarttv.connectsdk.ConnectSdkHandler.powerOnBLEWakeup(...)`:

```
targetAddress = macBytes( NetworkManager.getAddress(BT) )          // the PHONE's identity (see below)
info = new LeccpControlAdvertisingInfo(targetAddress)
info.addControlData(controlId = 0x00, macBytes(device."registered_bt_address"))   // the TV's BT addr, stored at registration
AdvertiseManager.startAdvertise(BLE, info.getBytes())              // company 0x00C4, service 0xFEBA
```

`LeccpControlAdvertisingInfo.getBytes()` serialises as `targetAddress ++ [byteSize][controlId][controlData]`. So the
14-byte manufacturer payload decodes as:

| bytes | value (this phone+TV) | meaning |
|---|---|---|
| `0a 1b 2c 3d 4e 5f` | ThinQ's stored random MAC | `targetAddress` = `NetworkManager.getAddress(BT)` |
| `07 00` | len `0x07` (captured), controlId `0x00` | control-block header (the code reads as `controlData.length + 2` = 8; the captures and the working replay say 7) |
| `a4 ce da 61 d0 d5` | the TV's BT address (= Wi-Fi MAC + 1) | `controlData`, read from `registered_bt_address` |

**The leading 6 bytes are not a key and not the phone's real BT MAC.** `BtNetwork.getAddress()` does:

```
v = SettingsDb.getString(KEY_MAC_ADDRESS, null)
if (v == null) { v = NetworkUtil.getOrCreateRandomizedMacAddress(); SettingsDb.set(KEY_MAC_ADDRESS, v) }
return v
```

`getOrCreateRandomizedMacAddress()` → `createRandomUnicastAddress()`: a **random locally-administered MAC, generated
once and persisted** in ThinQ's settings DB. That is why `0a:1b:2c:3d:4e:5f` has the locally-administered bit set
(`0x96`) and matches no interface on the phone (the real public BT MAC is `9C:9E:D5:75:71:86`; the shell, without
root, sees only redacted/randomised MACs, none equal to it).

**Answers to the old open questions:**
- *Per phone?* Yes — it's a value ThinQ invents and stores per install, registered with the TV at pairing.
- *Derived from the TV / an account secret?* No. No crypto, no cloud, no handshake needed to replay.
- *Rolling / reset?* **Stable** across reboots and Bluetooth toggles (create-once-persist). It changes only if ThinQ's
  data is cleared or the app is reinstalled — then the TV must be re-registered. This is the likely cause of ThinQ's
  occasional "can't wake / power button greyed".

### Security view (why this matters)
- The wake credential is random but travels **in cleartext in a connectionless advertisement** — entropy is moot once
  it is on the air. One passive sniff gives permanent, unauthenticated wake capability (confirmed by the replay tests).
- It is **stable and per-install**, so there is no capture race; the TV's half is openly derivable (`Wi-Fi MAC + 1`,
  and the TV broadcasts it on service `0xFEB9`).
- Blast radius is power-on only (availability/energy); waking grants no session or control.
- ThinQ already generates this value randomly and stores it, so a rotating/challenge token over the connected
  LECCP/GATT channel would have cost LG no more — the static shared identifier is a design choice.

Not yet reconciled: the field captures recorded the header byte as `07`, where the code computes `08` (`byteSize` =
`controlData.length + 2`); a fresh btsnoop would settle it. It does not change the conclusion.

## Tools

- `tools/btsnoop.py`: parses btsnoop, lists the LE advertising, scan and connection
  commands, and counts ACL traffic.
- The log's timestamps read 5 h behind local time (UTC on the phone's clock), consistently.
