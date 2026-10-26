package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.ConsumerIrManager

/**
 * Turning the TV on with the phone's IR blaster, the way a universal remote does (docs/research/ir-power-on.md): no
 * network, no pairing, no registration — line of sight only. The TV obeys IR power even in deep standby, like its own
 * remote. Only useful when the phone has an emitter ([hasEmitter]); most don't, so the UI gates on it.
 *
 * shortcut: LG NEC codes (2026-10-26), unverified on real hardware yet — test both on Juan's 70UP7500PSC and keep the
 * one that wakes it. Promote to the Turn-on fallback (behind a working Bluetooth key) once confirmed.
 */
object IrWake {
    /** LG uses NEC at ~38 kHz. Discrete ON won't toggle a TV that's already on; toggle is the universal fallback. */
    const val LG_POWER_ON = 0x20DF23DC.toInt()
    const val LG_POWER_TOGGLE = 0x20DF10EF.toInt()

    fun hasEmitter(context: Context): Boolean =
        (context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.hasIrEmitter() == true

    /**
     * NEC waveform for a 32-bit [code], MSB first, as on/off durations in microseconds:
     * 9 ms lead + 4.5 ms space, then 32 bits (560 µs mark; 1690 µs space = 1, 560 µs space = 0), then a 560 µs stop.
     */
    fun nec(code: Int): IntArray {
        val p = IntArray(2 + 64 + 1)
        p[0] = 9000; p[1] = 4500
        for (i in 0 until 32) {
            val one = (code ushr (31 - i)) and 1 == 1
            p[2 + i * 2] = 560
            p[2 + i * 2 + 1] = if (one) 1690 else 560
        }
        p[66] = 560
        return p
    }

    /** Transmits [code] at 38 kHz; false when there's no emitter or the permission is missing. */
    @SuppressLint("MissingPermission") // TRANSMIT_IR: harmless if denied (transmit throws, caught here)
    fun powerOn(context: Context, code: Int = LG_POWER_ON): Boolean {
        val ir = context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager ?: return false
        if (!ir.hasIrEmitter()) return false
        return runCatching { ir.transmit(38000, nec(code)) }.onFailure { log { "ir transmit failed: $it" } }.isSuccess
    }
}
