package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Known answers from controlled-pc's crypto.rs: both sides must agree byte for byte. */
class SmoothCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val phonePub = hex("046ff03b949241ce1dadd43519e6960e0a85b41a69a05c328103aa2bce1594ca163c4f753a55bf01dc53f6c0b0c7eee78b40c6ff7d25a96e2282b989cef71c144a")
    private val pcPub = hex("04550f471003f3df97c3df506ac797f6721fb1a1fb7b8f6f83d224498a65c88e24136093d7012e509a73715cbd0b00a3cc0ff4b5c01b3ffa196ab1fb327036b8e6")
    private val phoneNonce = ByteArray(16) { 0xA1.toByte() }
    private val pcNonce = ByteArray(16) { 0xB2.toByte() }
    private val move = byteArrayOf('M'.code.toByte(), 3, 0, 0xFD.toByte(), 0xFF.toByte())

    @Test fun codeMatchesThePc() = assertEquals("798709", SmoothCrypto.code(phonePub, pcPub, phoneNonce, pcNonce))

    @Test fun sealMatchesThePc() {
        val keys = SmoothCrypto.keys(SmoothCrypto.privateKey(ByteArray(32) { 1 }), pcPub, phoneNonce, pcNonce)!!
        val sealed = SmoothCrypto.seal(keys.toPc, SmoothCrypto.UDP, 7, move)
        assertArrayEquals(hex("0000000000000007afb8f36ff3d6b81d85d58c0f8507f838d8b6193585"), sealed)
        assertArrayEquals(move, SmoothCrypto.open(keys.toPc, SmoothCrypto.UDP, sealed)!!.second)
        assertNull(SmoothCrypto.open(keys.toPc, SmoothCrypto.TCP, sealed))
        assertNull(SmoothCrypto.open(keys.toPhone, SmoothCrypto.UDP, sealed))
    }

    @Test fun publicKeyRoundTrips() {
        val pair = SmoothCrypto.newKeyPair()
        assertArrayEquals(SmoothCrypto.publicBytes(pair.public), SmoothCrypto.publicBytes(SmoothCrypto.publicKey(SmoothCrypto.publicBytes(pair.public))!!))
        assertNull(SmoothCrypto.publicKey(ByteArray(65)))
    }
}
