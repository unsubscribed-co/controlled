package dev.unsubscribed.controlled

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Power on: the magic packet to each of the TV's MACs, on the Wi-Fi's subnet broadcast and 255.255.255.255, UDP 9. */
object WakeOnLan {
    /** 6 × 0xFF then the MAC 16 times: 102 bytes. Null for anything that isn't 6 hex pairs. */
    fun packet(mac: String): ByteArray? {
        val bytes = mac.split(':', '-').takeIf { it.size == 6 }?.map { it.toIntOrNull(16)?.toByte() ?: return null } ?: return null
        return ByteArray(6) { 0xFF.toByte() } + List(16) { bytes }.flatten()
    }

    /** 192.168.1.37/24 -> 192.168.1.255. */
    fun broadcast(address: Inet4Address, prefix: Int): InetAddress {
        val ip = address.address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)
        val b = ip or mask.inv()
        return InetAddress.getByAddress(byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte()))
    }

    suspend fun send(context: Context, macs: List<String>) = withContext(Dispatchers.IO) {
        val targets = listOfNotNull(wifiBroadcast(context), InetAddress.getByName("255.255.255.255"))
        DatagramSocket().use { socket ->
            socket.broadcast = true
            log { "wake: macs=$macs targets=$targets" }
            for (packet in macs.mapNotNull(::packet)) for (target in targets) {
                runCatching { socket.send(DatagramPacket(packet, packet.size, target, 9)) }.onFailure { log { "wake to $target failed: $it" } }
            }
        }
    }

    private fun wifiBroadcast(context: Context): InetAddress? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: return null
        val link = cm.getLinkProperties(wifi)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        return broadcast(link.address as Inet4Address, link.prefixLength)
    }
}
