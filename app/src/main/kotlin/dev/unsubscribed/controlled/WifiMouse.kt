package dev.unsubscribed.controlled

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Step 1 prototype of smooth mode (docs/plans/pc-wifi.md): draws the same back-and-forth stroke as the BLE spike,
 * 3 px every 8 ms for 20 s, as UDP packets to controlled-pc. Dev only: --es wifi_mouse <pc ip>.
 */
object WifiMouse {
    fun draw(host: String) = Thread {
        val to = InetSocketAddress(host, 47805)
        DatagramSocket().use { s ->
            val end = System.nanoTime() + 20_000_000_000L
            var t = 0
            var next = System.nanoTime()
            while (System.nanoTime() < end) {
                val dx = if ((t++ / 250) % 2 == 0) 3 else -3
                val p = byteArrayOf('M'.code.toByte(), (dx and 0xFF).toByte(), (dx shr 8).toByte(), 0, 0)
                s.send(DatagramPacket(p, p.size, to))
                next += 8_000_000L
                val wait = (next - System.nanoTime()) / 1_000_000L
                if (wait > 0) Thread.sleep(wait)
            }
            log { "wifi mouse sent $t" }
        }
    }.start()
}
