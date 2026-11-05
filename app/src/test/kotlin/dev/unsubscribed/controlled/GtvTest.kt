package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey

/** The expected bytes are what the throwaway Python client sent to the onn stick, which accepted them (2026-11-05). */
class GtvTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun keyPress() = assertArrayEquals(hex("520408161003"), Gtv.key(Gtv.RIGHT))

    @Test fun textAtCursorWithZeroCounters() = assertArrayEquals(hex("aa0112080010001a0c08011208080110011a026162"), Gtv.text("ab"))

    @Test fun appLink() = assertArrayEquals(hex("d205190a1768747470733a2f2f7777772e796f75747562652e636f6d"), Gtv.appLink("https://www.youtube.com"))

    @Test fun pairingRequest() = assertArrayEquals(hex("080210c80152170a0961747672656d6f7465120a436f6e74726f6c6c6564"), Gtv.pairingRequest("Controlled"))

    @Test fun varintFrame() {
        assertArrayEquals(hex("ac02"), Gtv.varint(300))
        assertEquals(302, Gtv.frame(ByteArray(300)).size) // 2 length bytes + 300
    }

    @Test fun parseRoundTrip() {
        val m = Gtv.msg(40, Gtv.int(1, 1)) + Gtv.str(3, "x")
        val fields = Gtv.parse(m)
        assertEquals(1L, Gtv.field(fields[0].second as ByteArray, 1))
        assertEquals("x", String(fields[1].second as ByteArray))
    }

    @Test fun statusOfReply() = assertEquals(200, Gtv.status(Gtv.pairingOptions()))

    private fun rsa(n: Long, e: Long) = object : RSAPublicKey {
        override fun getModulus() = BigInteger.valueOf(n)
        override fun getPublicExponent() = BigInteger.valueOf(e)
        override fun getAlgorithm() = "RSA"
        override fun getFormat() = null
        override fun getEncoded() = null
    }

    // Made-up small numbers, computed with Python's hashlib: the formula, not real keys.
    private val client = rsa(0xC0FFEE1234, 65537)
    private val tv = rsa(0xABCDEF99, 3)

    @Test fun secretKnownAnswer() =
        assertArrayEquals(hex("9b10878285e7b726cc2e4a2a96a2f3f9c0f0fb3622dabeb70a8c9dc4aa514648"), Gtv.secret(client, tv, "9b1a2b"))

    @Test fun secretRejectsTypos() {
        assertNull(Gtv.secret(client, tv, "9C1A2B")) // first byte doesn't match the hash
        assertNull(Gtv.secret(client, tv, "9B1A2")) // too short
        assertNull(Gtv.secret(client, tv, "9B1A2G")) // not hex
    }

    @Test fun unsignedDropsSignByte() = assertArrayEquals(hex("c0ffee"), Gtv.unsigned(BigInteger("c0ffee", 16)))

    @Test fun unseenAppOpensItsPlayPage() {
        assertEquals("market://details?id=com.disney.disneyplus", Gtv.launchLink("com.disney.disneyplus", emptyList()))
        assertEquals("https://www.disneyplus.com", Gtv.launchLink("com.disney.disneyplus", listOf("com.disney.disneyplus")))
        assertNull(Gtv.launchLink("com.unknown", emptyList()))
    }

    @Test fun forgottenPhoneIsTheCertificateAlert() {
        val alert = javax.net.ssl.SSLHandshakeException("Read error: error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN")
        assert(Gtv.forgotUs(TvException(TvException.Kind.Unreachable, "remote", alert)))
        assert(!Gtv.forgotUs(java.net.SocketTimeoutException("connect timed out")))
    }

    @Test fun buttonsCoverTheRemote() {
        for (name in listOf("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "HOME", "MENU")) assert(name in Gtv.BUTTONS)
    }
}
