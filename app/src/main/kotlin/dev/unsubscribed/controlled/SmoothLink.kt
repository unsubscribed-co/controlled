package dev.unsubscribed.controlled

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Smooth mode's connection to controlled-pc (docs/plans/pc-wifi.md): hello over TCP, the PC asks the person to check
 * the 6-digit code [onCode] shows here, then input goes sealed: moves and scroll over UDP, everything else over TCP.
 * Blocking calls: run off the main thread.
 */
class SmoothLink(context: Context) {
    private val keyFile = File(context.filesDir, "smooth.key")
    /**
     * How the PC lists this phone: the name in Settings > About phone ("POCO X7 Pro"), not the model code Xiaomi puts
     * in Build.MODEL ("2412DPC0AG"). The dev app says so, as it's a second app on the same phone.
     */
    private val name = (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}") + if (BuildConfig.FLAVOR == "dev") " (Dev)" else ""
    private val tcp = Socket()
    private val udp = DatagramSocket()
    private lateinit var out: DataOutputStream
    private lateinit var keys: SmoothCrypto.Keys
    private lateinit var pc: InetSocketAddress
    private var tcpCounter = 1L
    private var udpCounter = 0L

    /** This phone's long-term key: made once, kept in the app's private storage. */
    private fun identity(): Pair<java.security.PrivateKey, ByteArray> {
        if (keyFile.length() == 32L + 65L) {
            val b = keyFile.readBytes()
            return SmoothCrypto.privateKey(b.copyOfRange(0, 32)) to b.copyOfRange(32, 97)
        }
        val pair = SmoothCrypto.newKeyPair()
        val s = (pair.private as ECPrivateKey).s.toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else ByteArray(32 - it.size) + it }
        val pub = SmoothCrypto.publicBytes(pair.public)
        keyFile.writeBytes(s + pub)
        return SmoothCrypto.privateKey(s) to pub
    }

    /** Connects and, the first time, shows [onCode] while the person allows this phone on the PC. True once allowed. */
    fun connect(to: InetSocketAddress, onCode: (String) -> Unit) = connect(to.address.hostAddress!!, to.port, onCode)

    fun connect(host: String, port: Int = 47805, onCode: (String) -> Unit): Boolean {
        val (private, phonePub) = identity()
        pc = InetSocketAddress(InetAddress.getByName(host), port)
        tcp.connect(pc, 3000)
        tcp.tcpNoDelay = true
        out = DataOutputStream(tcp.getOutputStream())
        val input = DataInputStream(tcp.getInputStream())
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
        frame(byteArrayOf('H'.code.toByte(), 1) + phonePub + nonce + name.toByteArray())
        val hello = ByteArray(input.readUnsignedShort()).also(input::readFully)
        if (hello.size < 2 + 65 + 16 + 1 || hello[0] != 'H'.code.toByte() || hello[1] != 1.toByte()) return false
        val pcPub = hello.copyOfRange(2, 67)
        val pcNonce = hello.copyOfRange(67, 83)
        val known = hello[83] == 1.toByte()
        keys = SmoothCrypto.keys(private, pcPub, nonce, pcNonce) ?: return false
        if (!known) onCode(SmoothCrypto.code(phonePub, pcPub, nonce, pcNonce))
        tcp.soTimeout = 90_000
        val ok = runCatching { ByteArray(input.readUnsignedShort()).also(input::readFully) }.onFailure { log { "smooth: waiting for the PC: $it" } }.getOrNull() ?: return false
        tcp.soTimeout = 0
        return SmoothCrypto.open(keys.toPhone, SmoothCrypto.TCP, ok)?.second?.contentEquals("OK".toByteArray()) == true
    }

    private fun frame(b: ByteArray) {
        out.writeShort(b.size); out.write(b); out.flush()
    }

    fun move(dx: Int, dy: Int) = datagram(byteArrayOf('M'.code.toByte(), dx.toByte(), (dx shr 8).toByte(), dy.toByte(), (dy shr 8).toByte()))

    fun scroll(vertical: Int, horizontal: Int) = datagram(byteArrayOf('S'.code.toByte(), vertical.coerceIn(-127, 127).toByte(), horizontal.coerceIn(-127, 127).toByte()))

    fun text(t: String) = event(byteArrayOf('T'.code.toByte()) + t.toByteArray())

    /**
     * One of PcHid's Bluetooth reports, sent the smooth way: keyboard and button state over TCP (the PC diffs them, so
     * nothing stays held), wheel over UDP, media keys over TCP. Moves don't come here: [move] sends them directly.
     */
    fun report(id: Int, r: ByteArray) {
        when (id) {
            Hid.KEYBOARD -> event(byteArrayOf('R'.code.toByte()) + r)
            Hid.MOUSE -> {
                event(byteArrayOf('B'.code.toByte(), r[0]))
                if (r.size >= 7 && (r[5] != 0.toByte() || r[6] != 0.toByte())) datagram(byteArrayOf('S'.code.toByte(), r[5], r[6]))
            }
            Hid.CONSUMER -> event(byteArrayOf('C'.code.toByte(), r[0], r[1]))
        }
    }

    /**
     * Blocks until the PC closes the connection, it breaks, or (from PC 1.0.1, which sends an empty frame every 2 s)
     * the heartbeat stops: a PC asleep or off the network sends no goodbye. Older PCs send none, so the wait only
     * starts with the first one.
     */
    fun awaitClose() {
        runCatching {
            val input = DataInputStream(tcp.getInputStream())
            while (true) {
                val n = input.readUnsignedShort()
                if (n == 0) tcp.soTimeout = HEARTBEAT_LOST_MS else input.skipBytes(n)
            }
        }
    }

    private fun datagram(e: ByteArray) {
        val sealed = SmoothCrypto.seal(keys.toPc, SmoothCrypto.UDP, ++udpCounter, e)
        udp.send(DatagramPacket(sealed, sealed.size, pc))
    }

    @Synchronized private fun event(e: ByteArray) = frame(SmoothCrypto.seal(keys.toPc, SmoothCrypto.TCP, ++tcpCounter, e))

    fun close() {
        runCatching { tcp.close() }; udp.close()
    }
}

/** Three missed heartbeats (PC 1.0.1 sends one every 2 s): the PC is gone, back to Bluetooth. */
private const val HEARTBEAT_LOST_MS = 6_000

/** Of the addresses mDNS gives, the IPv4 one: PC 1.0.0 listens on IPv4 only (a link-local IPv6 timed out). */
fun smoothHost(addresses: List<InetAddress>): InetAddress? = addresses.firstOrNull { it is Inet4Address }

/**
 * Finds controlled-pc on this Wi-Fi by mDNS: the program named like [pcName] (the PC's own name, which is also its
 * Bluetooth name), or null within [timeoutMs]. Blocking: call off the main thread.
 */
@Suppress("DEPRECATION") // resolveService: its replacement is API 34+.
fun findSmoothPc(context: Context, pcName: String, timeoutMs: Long = 4000): InetSocketAddress? {
    val nsd = context.getSystemService(NsdManager::class.java)
    val done = CountDownLatch(1)
    var found: InetSocketAddress? = null
    val resolve = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
        override fun onServiceResolved(info: NsdServiceInfo) {
            val all = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
            smoothHost(all)?.let { found = InetSocketAddress(it, info.port); done.countDown() }
        }
    }
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, error: Int) = done.countDown()
        override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName.equals(pcName, ignoreCase = true)) runCatching { nsd.resolveService(info, resolve) }
        }
    }
    runCatching { nsd.discoverServices("_controlled._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return null }
    done.await(timeoutMs, TimeUnit.MILLISECONDS)
    runCatching { nsd.stopServiceDiscovery(listener) }
    return found
}
