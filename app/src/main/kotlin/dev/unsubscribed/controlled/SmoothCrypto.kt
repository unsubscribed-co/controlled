package dev.unsubscribed.controlled

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Smooth mode's security, the phone's half of controlled-pc's crypto.rs (same known answers in SmoothCryptoTest):
 * P-256 ECDH of both long-term keys, salted with both hellos' nonces, gives the session keys (HKDF-SHA256); pairing shows
 * a 6-digit code on both screens; every message after the hello is AES-256-GCM with a channel and a growing counter.
 */
object SmoothCrypto {
    const val TCP = 0
    const val UDP = 1

    class Keys(val toPc: SecretKeySpec, val toPhone: SecretKeySpec)

    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
    }

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun privateKey(scalar: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, scalar), params))

    /** SEC1 uncompressed: 0x04, x, y (32 bytes each). */
    fun publicBytes(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
    }

    fun publicKey(sec1: ByteArray): PublicKey? {
        if (sec1.size != 65 || sec1[0] != 4.toByte()) return null
        val point = ECPoint(BigInteger(1, sec1.copyOfRange(1, 33)), BigInteger(1, sec1.copyOfRange(33, 65)))
        return runCatching { KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) }.getOrNull()
    }

    fun keys(phone: PrivateKey, pcPub: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray): Keys? {
        val pc = publicKey(pcPub) ?: return null
        val shared = KeyAgreement.getInstance("ECDH").run { init(phone); doPhase(pc, true); generateSecret() }
        val okm = hkdf(phoneNonce + pcNonce, shared, "controlled smooth v1".toByteArray(), 64)
        return Keys(SecretKeySpec(okm.copyOfRange(0, 32), "AES"), SecretKeySpec(okm.copyOfRange(32, 64), "AES"))
    }

    fun code(phonePub: ByteArray, pcPub: ByteArray, phoneNonce: ByteArray, pcNonce: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").run {
            update("controlled code".toByteArray()); listOf(phonePub, pcPub, phoneNonce, pcNonce).forEach(::update); digest()
        }
        val n = ((d[0].toLong() and 0xFF) shl 24) or ((d[1].toLong() and 0xFF) shl 16) or ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)
        return "%06d".format(n % 1_000_000)
    }

    fun seal(key: SecretKeySpec, channel: Int, counter: Long, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(channel, counter))) }
        return be(counter) + c.doFinal(plain)
    }

    /** The counter and message of a sealed frame, or null if it was tampered with or isn't for this key. */
    fun open(key: SecretKeySpec, channel: Int, frame: ByteArray): Pair<Long, ByteArray>? {
        if (frame.size < 8 + 16) return null
        val counter = frame.copyOfRange(0, 8).fold(0L) { a, b -> (a shl 8) or (b.toLong() and 0xFF) }
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(channel, counter))) }
            counter to c.doFinal(frame, 8, frame.size - 8)
        }.getOrNull()
    }

    private fun nonce(channel: Int, counter: Long) = ByteArray(12).also { it[0] = channel.toByte(); be(counter).copyInto(it, 4) }

    private fun be(v: Long) = ByteArray(8) { (v shr (56 - 8 * it)).toByte() }

    private fun fixed(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(salt, "HmacSHA256")); doFinal(ikm) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        var t = ByteArray(0)
        var out = ByteArray(0)
        var i = 1
        while (out.size < length) {
            mac.update(t); mac.update(info); mac.update(i++.toByte())
            t = mac.doFinal()
            out += t
        }
        return out.copyOf(length)
    }
}
