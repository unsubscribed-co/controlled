package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.concurrent.schedule

/** Why smooth mode isn't on: the PC program isn't there, Wi-Fi is off, they're on different networks or one that
 * keeps devices apart, smooth mode is paused on the PC or another phone has it, the PC reached this phone but the
 * meeting didn't finish, the PC said no; or a session that [Ended]. */
enum class SmoothEnd { NoProgram, NoWifi, OtherNetwork, Isolated, Paused, Busy, Interrupted, Declined, Ended }

/**
 * Smooth mode's connection to Controlled for PC (docs/plans/pc-bluetooth-rendezvous.md). The phone opens Controlled's
 * Bluetooth channel on the PC it's paired with: the hello, and the first time the 6-digit code [onCode] shows here,
 * go there. Then the phone says where it waits on Wi-Fi and the PC dials it: sealed events over that TCP connection,
 * sealed moves over UDP to where the PC's own packets come from. Nothing on the PC is open to the network, so no
 * firewall or network type gets in the way, and no search by name. Blocking calls: run off the main thread.
 */
class SmoothLink(context: Context) {
    private val keyFile = File(context.filesDir, "smooth.key")
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    /**
     * How the PC lists this phone: the name in Settings > About phone ("POCO X7 Pro"), not the model code Xiaomi puts
     * in Build.MODEL ("2412DPC0AG"). The dev app says so, as it's a second app on the same phone.
     */
    private val name = (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}") + if (BuildConfig.FLAVOR == "dev") " (Dev)" else ""
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null
    @Volatile private var bt: BluetoothSocket? = null
    @Volatile private var closed = false
    private lateinit var out: DataOutputStream
    private lateinit var keys: SmoothCrypto.Keys
    private lateinit var pcUdp: SocketAddress
    private var tcpCounter = 1L
    private var udpCounter = 0L
    /** When the PC couldn't reach this phone: its address and this phone's, to show the person. */
    var addresses: Pair<String, String>? = null
        private set

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

    /**
     * This phone's IPv4 addresses the PC may reach, with their networks' prefixes: Wi-Fi first, then a hotspot or
     * Ethernet it shares (a laptop on the phone's own hotspot). Not mobile data or a VPN: no PC reaches the phone there.
     */
    @Suppress("DEPRECATION") // allNetworks: the callback alternative is overkill for one look
    private fun reachable(): List<Pair<Inet4Address, Int>> {
        val wifi = mutableSetOf<String>()
        val skip = mutableSetOf<String>()
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            val iface = cm.getLinkProperties(n)?.interfaceName ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi += iface
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) skip += iface
        }
        val all = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { i -> runCatching { i.isUp && !i.isLoopback }.getOrDefault(false) && skip.none { i.name == it || i.name.endsWith("-$it") } }
            .sortedBy { if (it.name in wifi) 0 else 1 }
        return all.flatMap { i -> i.interfaceAddresses.mapNotNull { a -> (a.address as? Inet4Address)?.takeIf { !it.isLinkLocalAddress }?.let { it to a.networkPrefixLength.toInt() } } }
            .distinctBy { it.first }.take(8)
    }

    private fun DataOutputStream.frame(b: ByteArray) {
        writeShort(b.size); write(b); flush()
    }

    private fun DataInputStream.frame() = ByteArray(readUnsignedShort()).also(::readFully)

    /**
     * Meets the PC over Bluetooth and, the first time, shows [onCode] while the person allows this phone there; then
     * waits for the PC to dial in over Wi-Fi. Null once smooth mode can run, else why not. Every wait has a limit (a
     * Bluetooth read has no timeout of its own, so a timer closes the socket): a PC program that hangs can't leave
     * smooth mode stuck trying.
     */
    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is granted before a PC controller opens (PcScreen)
    fun connect(pc: BluetoothDevice, onCode: (String) -> Unit): SmoothEnd? {
        val here = reachable().takeIf { it.isNotEmpty() } ?: return SmoothEnd.NoWifi
        val (private, phonePub) = identity()
        val bt = runCatching { pc.createRfcommSocketToServiceRecord(SERVICE).also { this.bt = it; if (closed) it.close() } }
            .getOrNull() ?: return SmoothEnd.NoProgram
        val timer = Timer(true)
        var limit: TimerTask? = null
        fun within(ms: Long) {
            limit?.cancel()
            limit = timer.schedule(ms) { runCatching { bt.close() } }
        }
        within(STEP_MS) // the connect too: one that never returns can't keep smooth mode from trying again
        runCatching { bt.connect() }.onFailure {
            log { "smooth: no Controlled for PC over Bluetooth: $it" }
            timer.cancel()
            runCatching { bt.close() }
            return SmoothEnd.NoProgram
        }
        var asked = false
        try {
            within(STEP_MS)
            val btIn = DataInputStream(bt.inputStream)
            val btOut = DataOutputStream(bt.outputStream)
            val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
            btOut.frame(byteArrayOf('H'.code.toByte(), 2) + phonePub + nonce + name.toByteArray())
            val hello = btIn.frame()
            if (hello.contentEquals(byteArrayOf('Z'.code.toByte()))) return SmoothEnd.Paused
            if (hello.contentEquals(byteArrayOf('B'.code.toByte()))) return SmoothEnd.Busy
            if (hello.size < 2 + 65 + 16 + 1 || hello[0] != 'H'.code.toByte() || hello[1] != 2.toByte()) return SmoothEnd.NoProgram
            val pcPub = hello.copyOfRange(2, 67)
            val pcNonce = hello.copyOfRange(67, 83)
            keys = SmoothCrypto.keys(private, pcPub, nonce, pcNonce) ?: return SmoothEnd.NoProgram
            if (hello[83] != 1.toByte()) {
                asked = true
                within(ALLOW_MS) // the PC waits 85 s for the person
                onCode(SmoothCrypto.code(phonePub, pcPub, nonce, pcNonce))
            }
            val answer = SmoothCrypto.open(keys.toPhone, SmoothCrypto.TCP, btIn.frame())?.second
            if (answer?.contentEquals("OK".toByteArray()) != true) return SmoothEnd.Declined
            asked = false // allowed: from here, a dropped line is the network's
            within(STEP_MS)
            ServerSocket(0).use { server ->
                val u = DatagramSocket(0)
                udp = u
                val where = byteArrayOf('W'.code.toByte()) + port(server.localPort) + port(u.localPort) +
                    here.fold(ByteArray(0)) { b, (ip, _) -> b + ip.address }
                btOut.frame(SmoothCrypto.seal(keys.toPc, SmoothCrypto.TCP, ++tcpCounter, where))
                val s = proven(server) ?: return whyNot(btIn, here)
                tcp = s
                out = DataOutputStream(s.getOutputStream())
                // The PC's first UDP packet (another every 2 s): where its moves socket is, behind a router of its own
                // the router's.
                pcUdp = firstFromPc(u) ?: return SmoothEnd.Isolated
            }
            heartbeat()
            return null
        } catch (e: IOException) {
            log { "smooth: $e" }
            return if (asked) SmoothEnd.Declined else SmoothEnd.NoProgram // asked and the line went: nobody allowed it
        } finally {
            timer.cancel()
            runCatching { bt.close() }
        }
    }

    /** The PC's connection, once it proves it holds this session's keys (anything else that knocks is turned away). */
    private fun proven(server: ServerSocket): Socket? {
        val until = System.currentTimeMillis() + WAIT_MS
        while (true) {
            server.soTimeout = (until - System.currentTimeMillis()).toInt().takeIf { it > 0 } ?: return null
            val c = try { server.accept() } catch (_: SocketTimeoutException) { return null }
            c.soTimeout = 500 // the PC sends its proof at once; something that just connects can't hold the door
            val proof = runCatching { DataInputStream(c.getInputStream()).frame() }.getOrNull()
            val p = proof?.let { SmoothCrypto.open(keys.toPhone, SmoothCrypto.TCP, it) }
            if (p != null && p.first == 2L && p.second.contentEquals("P".toByteArray())) {
                c.soTimeout = 0
                c.tcpNoDelay = true
                return c
            }
            c.close()
        }
    }

    /** Where the PC's sealed UDP packets come from (anything else that arrives is skipped), within 3 s. */
    private fun firstFromPc(u: DatagramSocket): SocketAddress? {
        val until = System.currentTimeMillis() + 3_000
        val p = DatagramPacket(ByteArray(64), 64)
        while (true) {
            u.soTimeout = (until - System.currentTimeMillis()).toInt().takeIf { it > 0 } ?: return null
            if (runCatching { u.receive(p) }.isFailure) return null
            if (SmoothCrypto.open(keys.toPhone, SmoothCrypto.UDP, p.data.copyOf(p.length)) != null) return p.socketAddress
        }
    }

    /**
     * The PC didn't get through: it says where it tried to dial from, and that tells which of the two it is. No word
     * at all means it did dial in but something stopped the meeting there (it isn't the network's layout).
     */
    private fun whyNot(btIn: DataInputStream, here: List<Pair<Inet4Address, Int>>): SmoothEnd {
        val f = runCatching { SmoothCrypto.open(keys.toPhone, SmoothCrypto.TCP, btIn.frame())?.second }.getOrNull()
            ?: return SmoothEnd.Interrupted
        val from = f?.takeIf { it.size == 5 && it[0] == 'F'.code.toByte() }?.copyOfRange(1, 5)
        val pcIp = from?.takeIf { b -> b.any { it != 0.toByte() } }?.let { InetAddress.getByAddress(it).hostAddress }
        addresses = (pcIp ?: "?") to here.first().first.hostAddress.orEmpty()
        val near = from != null && here.any { (ip, prefix) -> sameNetwork(ip.address, prefix, from) }
        return if (near) SmoothEnd.Isolated else SmoothEnd.OtherNetwork
    }

    /**
     * An empty frame every 2 s while nothing else goes to the PC, so it knows this phone is still there: when the
     * phone vanishes (asleep, off the Wi-Fi) the PC ends the session in 6 s and lets go of anything held.
     */
    private fun heartbeat() = Thread {
        while (true) {
            Thread.sleep(HEARTBEAT_MS)
            if (System.currentTimeMillis() - lastSent < HEARTBEAT_MS) continue
            runCatching { send(ByteArray(0)) }.onFailure { close(); return@Thread }
        }
    }.apply { isDaemon = true }.start()

    fun move(dx: Int, dy: Int) = datagram(byteArrayOf('M'.code.toByte(), dx.toByte(), (dx shr 8).toByte(), dy.toByte(), (dy shr 8).toByte()))

    fun scroll(vertical: Int, horizontal: Int) = datagram(byteArrayOf('S'.code.toByte(), vertical.coerceIn(-127, 127).toByte(), horizontal.coerceIn(-127, 127).toByte()))

    fun text(t: String) = textPieces(t).forEach { event(byteArrayOf('T'.code.toByte()) + it.toByteArray()) }

    /** PC 1.0.6+ ([canPaste]): [t] goes in through the PC's clipboard and Ctrl+V, whole, however long. */
    fun paste(t: String) = textPieces(t).forEach { event(byteArrayOf('P'.code.toByte()) + it.toByteArray()) }

    /** PC 1.0.6+ ([canPaste]): [n] Backspaces at once on the PC, instead of one event each. */
    fun erase(n: Int) = event(byteArrayOf('D'.code.toByte(), (n shr 8).toByte(), n.toByte()))

    /**
     * The PC said it takes [paste] and [erase] (1.0.6+). 2026-11-11: Windows 11 lost and repeated characters when a long
     * text was typed key by key (464 in 3.7 s; Windows 10 typed it exact), so pasted text goes in by Ctrl+V.
     */
    @Volatile var canPaste = false
        private set

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
     * Blocks until the PC closes the connection, it breaks, or the heartbeat (an empty frame every 2 s) stops: a PC
     * asleep or off the network sends no goodbye.
     */
    fun awaitClose(onFront: (Front) -> Unit, onCopied: (String) -> Unit) {
        val s = tcp ?: return
        runCatching {
            val input = DataInputStream(s.getInputStream())
            s.soTimeout = HEARTBEAT_LOST_MS
            var last = 2L // the proof's
            while (true) {
                val n = input.readUnsignedShort()
                if (n == 0) continue
                val f = ByteArray(n).also(input::readFully)
                val (counter, e) = SmoothCrypto.open(keys.toPhone, SmoothCrypto.TCP, f) ?: continue
                if (counter <= last) continue
                last = counter
                // What's in front, when it changes (E0 normal, E1 an administrator's window, E2 the secure screen).
                if (e.size == 2 && e[0] == 'E'.code.toByte()) Front.entries.getOrNull(e[1] - '0'.code.toByte())?.let(onFront)
                // What the PC takes beyond keys and text: 1 = paste and erase (PC 1.0.6+).
                if (e.size == 2 && e[0] == 'C'.code.toByte()) canPaste = e[1] >= 1
                // Text the person copied on the PC (PC 1.0.7+), for this phone's clipboard.
                if (e.size > 1 && e[0] == 'K'.code.toByte()) onCopied(String(e, 1, e.size - 1, Charsets.UTF_8))
            }
        }
    }

    private fun datagram(e: ByteArray) {
        val sealed = SmoothCrypto.seal(keys.toPc, SmoothCrypto.UDP, ++udpCounter, e)
        udp?.send(DatagramPacket(sealed, sealed.size, pcUdp))
    }

    @Volatile private var lastSent = 0L

    @Synchronized private fun send(frame: ByteArray) {
        out.frame(frame)
        lastSent = System.currentTimeMillis()
    }

    @Synchronized private fun event(e: ByteArray) = send(SmoothCrypto.seal(keys.toPc, SmoothCrypto.TCP, ++tcpCounter, e))

    fun close() {
        closed = true
        runCatching { bt?.close() }
        runCatching { tcp?.close() }; udp?.close()
    }

    companion object {
        /** Controlled's Bluetooth service, also in the PC program's bt.rs. */
        val SERVICE: UUID = UUID.fromString("6c0a3b2e-7e1d-4f6a-9b1e-2f0c0a7e5d11")
        /** How long the phone waits for the PC to dial in; the PC gives up dialing after 3 s and says why. */
        private const val WAIT_MS = 5_000L
        /** The longest any Bluetooth step may take, and the wait for the person to allow this phone on the PC. */
        private const val STEP_MS = 10_000L
        private const val ALLOW_MS = 95_000L
        private const val HEARTBEAT_MS = 2_000L
    }
}

private fun port(p: Int) = byteArrayOf((p shr 8).toByte(), p.toByte())

/** [t] in pieces of at most [max] chars (at 3 UTF-8 bytes each, well under a frame's 64 KB), never splitting a
 * surrogate pair. */
fun textPieces(t: String, max: Int = 8_000): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < t.length) {
        var end = minOf(i + max, t.length)
        if (end < t.length && Character.isHighSurrogate(t[end - 1])) end--
        out += t.substring(i, end)
        i = end
    }
    return out
}

/** Whether [other] is on the same network as [ip]/[prefix] (IPv4, 4 bytes each). */
fun sameNetwork(ip: ByteArray, prefix: Int, other: ByteArray): Boolean = (0 until prefix.coerceIn(0, 32)).all { bit ->
    val mask = 0x80 shr (bit % 8)
    (ip[bit / 8].toInt() and mask) == (other[bit / 8].toInt() and mask)
}

/** Three missed heartbeats (the PC sends one every 2 s): the PC is gone, back to Bluetooth. */
private const val HEARTBEAT_LOST_MS = 6_000
