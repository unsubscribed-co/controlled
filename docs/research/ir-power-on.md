# Turning the TV on with the phone's IR blaster

The universal, no-network way to power a TV on: the phone's infrared emitter, exactly as the plastic remote does. Works
from any TV state (including deep standby), on any brand, with no pairing, registration, or Wi-Fi — the one cost is
**line of sight**: the phone must be aimed at the TV. Most phones have no emitter; the feature gates on `hasIrEmitter()`.

Confirmed worth doing by a shipping universal remote, "TV Remote" (`com.controlla.rokuremoteapp`): it powers TVs on with
`ConsumerIrManager.transmit()` using a bundled per-brand IR code database (`assets/encryptedIRCodes/lg/…`, encrypted),
plus Wake-on-LAN + SSAP for network control. The LG-specific sibling (`com.controlla.lgremoteapp`) has **no** IR — only
WoL + SSAP — so it can't wake a TV past the ~1 h Wake-on-LAN window (same ceiling we measured). IR is the real fix.

## What Controlled does (`IrWake.kt`)

- `LG_POWER_ON = 0x20DF23DC` (NEC discrete power-on; won't toggle a TV that's already on), `LG_POWER_TOGGLE = 0x20DF10EF`.
- `nec(code)` builds the NEC waveform (microseconds, MSB first): 9 ms mark + 4.5 ms space, 32 bits (560 µs mark; space
  1690 µs = 1, 560 µs = 0), 560 µs stop. `powerOn()` transmits it at 38 kHz; false when there's no emitter.
- Wired into `RemoteModel.powerOn()` as `irWake()` — fires **only when no Bluetooth wake key is set** and the phone has
  an emitter. Bluetooth wake (hands-off, no aiming) is always preferred; IR is the second-best fallback.

Phone: POCO X7 Pro has an emitter (emitter on the top edge); `ir power ... emitter=true sent=true` confirmed, and a
third-party IR remote app powers Juan's 70UP7500PSC on, so IR → this TV works.

## Not yet verified
Whether **our** NEC codes (`0x20DF23DC` / `0x20DF10EF`) wake this TV — only that another app's IR does. The LG codes are
standard and high-confidence, but confirm before shipping: clear the wake key (so `irWake` fires) and tap Turn on at the
TV, or dev-trigger `am start ... --es ir_power on|toggle|<hex>` with the phone aimed. Keep whichever code wakes it.
