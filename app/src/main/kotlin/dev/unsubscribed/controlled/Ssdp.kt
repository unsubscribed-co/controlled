package dev.unsubscribed.controlled

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** A TV that answered the search. [ip] is the address we connect to; [name] is friendlyName without a leading "[LG] ". */
data class FoundTv(val ip: String, val name: String, val model: String?, val usn: String?)

object Ssdp {
    const val SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1"

    /** The M-SEARCH datagram text, CRLF line endings, ending with an empty line. MX 2. */
    fun searchMessage(): String =
        "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $SEARCH_TARGET\r\n\r\n"

    /** Header map of one SSDP answer, keys uppercased and trimmed; null when the first line isn't "HTTP/1.1 200". */
    fun parseAnswer(text: String): Map<String, String>? {
        val lines = text.lineSequence().map { it.trimEnd('\r', '\n') }.toList()
        val firstLine = lines.firstOrNull()?.trim() ?: return null
        if (!firstLine.startsWith("HTTP/1.1 200")) return null

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().uppercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    /** friendlyName, modelNumber from a device description XML; nulls when absent. Must not throw on bad XML. */
    fun parseDescription(xml: String): Pair<String?, String?> {
        return try {
            val friendlyName = extractTag(xml, "friendlyName")
            val modelNumber = extractTag(xml, "modelNumber")
            Pair(friendlyName, modelNumber)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    private fun extractTag(xml: String, tagName: String): String? {
        val regex = Regex("<$tagName(?:\\s+[^>]*)?>(.*?)</$tagName>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val raw = regex.find(xml)?.groupValues?.get(1)?.trim() ?: return null
        return if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) {
            raw.removeSurrounding("<![CDATA[", "]]>").trim()
        } else {
            raw
        }
    }

    /** "[LG] webOS TV UP7500PSC" -> "webOS TV UP7500PSC"; blank -> null. */
    fun displayName(friendlyName: String?): String? {
        val trimmed = friendlyName?.trim() ?: return null
        return (if (trimmed.startsWith("[LG]", ignoreCase = true)) trimmed.drop(4).trim() else trimmed).ifBlank { null }
    }

    /**
     * Searches for [timeoutMs] and emits each TV once (deduplicated by ip) as soon as its description is read.
     * Sends the M-SEARCH 3 times, 500 ms apart, to 239.255.255.250:1900 from a DatagramSocket bound to the Wi-Fi
     * interface's IPv4 address, holding a WifiManager.MulticastLock (tag "controlled-ssdp", reference counted false)
     * for the whole search and releasing it in finally. Reads answers with a short socket timeout so cancellation is
     * prompt. Fetches each LOCATION with HttpURLConnection (connect/read timeout 2000 ms). If the description can't be
     * fetched, still emit the TV with name = "LG TV" fallback and model null. Only accept LOCATION hosts that equal the
     * datagram's source address (ignore anything else). Runs on Dispatchers.IO.
     */
    fun discover(context: Context, timeoutMs: Long = 5000): Flow<FoundTv> = channelFlow {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = try {
            wifiManager?.createMulticastLock("controlled-ssdp")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            null
        }

        val seenIps = mutableSetOf<String>()
        var socket: DatagramSocket? = null

        try {
            // From the Wi-Fi's address, so the search doesn't go out on mobile data or a VPN.
            val udp = getWifiIpv4Address(context)?.let { runCatching { DatagramSocket(InetSocketAddress(it, 0)) }.getOrNull() }
                ?: DatagramSocket()
            socket = udp
            val multicastAddress = InetAddress.getByName("239.255.255.250")
            val searchBytes = searchMessage().toByteArray(Charsets.UTF_8)
            val searchPacket = DatagramPacket(searchBytes, searchBytes.size, multicastAddress, 1900)

            launch {
                repeat(3) { index ->
                    try {
                        udp.send(searchPacket)
                    } catch (_: Exception) {
                    }
                    if (index < 2) {
                        delay(500)
                    }
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(4096)
            val recvPacket = DatagramPacket(buffer, buffer.size)

            while (isActive && System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                udp.soTimeout = remaining.coerceAtMost(250).toInt().coerceAtLeast(1)

                try {
                    udp.receive(recvPacket)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: Exception) {
                    break
                }

                val senderAddress = recvPacket.address ?: continue
                val senderIp = senderAddress.hostAddress ?: continue
                val text = String(recvPacket.data, recvPacket.offset, recvPacket.length, Charsets.UTF_8)

                val headers = parseAnswer(text) ?: continue
                val location = headers["LOCATION"] ?: continue
                val locationUri = try { URI(location) } catch (_: Exception) { null } ?: continue
                val locationHost = locationUri.host ?: continue
                if (!locationHost.equals(senderIp, ignoreCase = true)) continue

                if (!seenIps.add(senderIp)) continue

                val usn = headers["USN"]
                launch {
                    val xml = fetchDescription(location)
                    val (friendlyName, model) = if (xml != null) {
                        parseDescription(xml)
                    } else {
                        Pair(null, null)
                    }
                    val name = displayName(friendlyName) ?: "LG TV"
                    send(FoundTv(ip = senderIp, name = name, model = model, usn = usn))
                }
            }
        } finally {
            socket?.close()
            try {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            } catch (_: Exception) {
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun getWifiIpv4Address(context: Context): Inet4Address? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val lp = cm.getLinkProperties(network) ?: continue
                for (linkAddress in lp.linkAddresses) {
                    val address = linkAddress.address
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address
                    }
                }
            }
        }
        return null
    }

    private fun fetchDescription(urlString: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as? HttpURLConnection)?.apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
                instanceFollowRedirects = true
            } ?: return null

            if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
