package dev.unsubscribed.controlled

import android.content.Context

/**
 * Smooth mode test driver (dev only): --es wifi_mouse <pc ip>. Connects with SmoothLink (logging the pairing code),
 * then draws the BLE spike's back-and-forth stroke, 3 px every 8 ms for 20 s, clicks once and types a word.
 */
object WifiMouse {
    fun draw(context: Context, host: String) = Thread {
        val link = SmoothLink(context)
        val ok = runCatching { link.connect(host) { code -> log { "wifi mouse code $code" } } }.onFailure { log { "wifi mouse: $it" } }.getOrDefault(false)
        log { "wifi mouse connected=$ok" }
        if (!ok) return@Thread link.close()
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
