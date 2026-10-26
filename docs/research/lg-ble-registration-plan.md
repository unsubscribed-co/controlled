# Plan: self-enrol Controlled as a Bluetooth waker (drop ThinQ for re-registration)

**Status: stopped at step 2 (2026-10-26).** The TV returns 401 insufficient permissions; see the result at the top of
`lg-ble-registration.md`.

**Why.** Deep-sleep wake works today because the TV has an allowed waker registered (done once via ThinQ) and
Controlled replays that advert. If the TV ever forgets the registration (factory reset, "forget devices"), re-enrolling
currently needs ThinQ again. This feature lets Controlled re-enrol itself, so ThinQ is never needed again.

**Key finding (see `lg-ble-registration.md`).** Enrolment is NOT a BLE/GATT pairing dance. ThinQ does it with one SSAP
call over Wi-Fi — `com.webos.service.asc/registerDevice` with `{type, address}` — a channel Controlled already speaks.
No PIN, no on-screen prompt, no key exchange. So re-enrolling is: ⋮ → Register for Bluetooth wake → done.

A draft is already in the working tree (uncommitted): `Registration.kt`, `RegistrationTest.kt`, a menu action in
`RemoteModel`/`RemoteScreen`, EN/ES strings, and the spec doc. This plan validates and finishes it.

## Steps

### 1. Review the draft (no hardware)
Confirm we're landing known code.
- `Registration.kt`: `randomWakeMac()` (random locally-administered unicast MAC, as ThinQ), `wakeKey() = mac + "0800"`
  (address + control header), SSAP const `com.webos.service.asc/registerDevice`, type `ThinQ`. (Reviewed — correct.)
- `RemoteModel.registerBleWake()`: generate-or-reuse MAC → SSAP call while connected → on success save as `bleKey`
  (reuses the existing `BleWake.send` path; one home for the value). Confirm it uses `TvClient`'s existing request path.
- Menu action + EN/ES strings + the assert test.

### 2. Live validation — THE GATE (TV on, phone on adb)
Build the dev APK, sideload, tap ⋮ → Register, expect the success toast. Two known failure modes, both with fixes:
- `asc/registerDevice` may need an SSAP permission Controlled's pairing manifest doesn't request → widen the register
  manifest, re-pair, retry.
- Registered address must equal the advertised bytes → log both, confirm match.
If registration succeeds, this step is done.

### 3. Deep-sleep proof
Clear the captured ThinQ key first (so we prove Controlled's OWN enrolment, not the old one). TV off, wait
>~1 h 10 min (past Wi-Fi wake), press Turn on → success = wakes from the BLE advert alone in ~5–10 s. Long wait; can run
overnight with the existing ARP monitor.

### 4. Finalise
- UX: keep the toast, or fold "Register this device" into the existing Bluetooth-wake-key setting (product call;
  recommend the toast — smallest change).
- Keep the assert test; update `lg-ble-registration.md` and `lg-power-on.md` with the hardware result.
- Checklist: no cost (not metered); it IS the free manual path; the key lives in `Store`; all user text EN+ES. Passes.

### 5. Ship (only on explicit "ship it")
Commit with the session's attribution lines, bump version, dev → prod per the Play release flow.

## Decisions that are Juan's
- UX: standalone menu action (as drafted) vs. a button inside the existing wake-key setting.
- Run the live test now (TV on) vs. after eyeballing the diff.
- Everything past step 2 waits on registration actually working on the TV.

## Open risk
Step 2's manifest-permission unknown is the only thing that could turn this from "one tap" into "re-pair + widen
manifest" — minor, but it's why the live test is the gate before anything is committed.
