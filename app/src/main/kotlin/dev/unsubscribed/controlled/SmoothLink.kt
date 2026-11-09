package dev.unsubscribed.controlled

import android.content.Context
import android.os.Build
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey

/**
 * Smooth mode's connection to controlled-pc (docs/plans/pc-wifi.md): hello over TCP, the PC asks the person to check
 * the 6-digit code [onCode] shows here, then input goes sealed: moves and scroll over UDP, everything else over TCP.
 * Blocking calls: run off the main thread.
 */
class SmoothLink(context: Context) {
    private val keyFile = File(context.filesDir, "smooth.key")
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
    fun connect(host: String, port: Int = 47805, onCode: (String) -> Unit): Boolean {
        val (private, phonePub) = identity()
        pc = InetSocketAddress(InetAddress.getByName(host), port)
        tcp.connect(pc, 3000)
        tcp.tcpNoDelay = true
        out = DataOutputStream(tcp.getOutputStream())
        val input = DataInputStream(tcp.getInputStream())
        val nonce = ByteArray(16).also(SecureRandom()::nextBytes)
        frame(byteArrayOf('H'.code.toByte(), 1) + phonePub + nonce + Build.MODEL.toByteArray())
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

    fun button(button: Int, down: Boolean) = event(byteArrayOf('B'.code.toByte(), button.toByte(), if (down) 1 else 0))

    fun text(t: String) = event(byteArrayOf('T'.code.toByte()) + t.toByteArray())

    private fun datagram(e: ByteArray) {
        val sealed = SmoothCrypto.seal(keys.toPc, SmoothCrypto.UDP, ++udpCounter, e)
        udp.send(DatagramPacket(sealed, sealed.size, pc))
    }

    @Synchronized private fun event(e: ByteArray) = frame(SmoothCrypto.seal(keys.toPc, SmoothCrypto.TCP, ++tcpCounter, e))

    fun close() {
        runCatching { tcp.close() }; udp.close()
    }
}
