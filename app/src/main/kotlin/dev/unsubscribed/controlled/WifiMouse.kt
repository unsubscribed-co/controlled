package dev.unsubscribed.controlled

import android.bluetooth.BluetoothManager
import android.content.Context

/**
 * Smooth mode test driver (dev only): --es wifi_mouse <PC Bluetooth address>. Connects with SmoothLink (logging the pairing code),
 * then draws the BLE spike's back-and-forth stroke, 3 px every 8 ms for 20 s. Moves only: no clicks, no keys.
 */
object WifiMouse {
    @android.annotation.SuppressLint("MissingPermission")
    fun draw(context: Context, pc: String) = Thread {
        val link = SmoothLink(context)
        val device = context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(pc)
        val end0 = runCatching { link.connect(device) { code -> log { "wifi mouse code $code" } } }.getOrElse { log { "wifi mouse: $it" }; SmoothEnd.NoProgram }
        log { "wifi mouse: ${end0 ?: "connected"} ${link.addresses ?: ""}" }
        if (end0 != null) return@Thread link.close()
        val end = System.nanoTime() + 20_000_000_000L
        var t = 0
        var next = System.nanoTime()
        while (System.nanoTime() < end) {
            link.move(if ((t++ / 250) % 2 == 0) 3 else -3, 0)
            next += 8_000_000L
            val wait = (next - System.nanoTime()) / 1_000_000L
            if (wait > 0) Thread.sleep(wait)
        }
        log { "wifi mouse sent $t" }
        link.close()
    }.start()
}
