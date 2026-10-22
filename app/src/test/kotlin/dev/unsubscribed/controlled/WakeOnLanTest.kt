package dev.unsubscribed.controlled

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class WakeOnLanTest {
    @Test fun packet() {
        val p = WakeOnLan.packet("a8:23:fe:01:02:0F")!!
        assertEquals(102, p.size)
        assertArrayEquals(ByteArray(6) { -1 }, p.copyOfRange(0, 6))
        val mac = byteArrayOf(0xA8.toByte(), 0x23, 0xFE.toByte(), 1, 2, 0x0F)
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + i * 6, 12 + i * 6))
        assertArrayEquals(p, WakeOnLan.packet("A8-23-FE-01-02-0f"))
    }

    @Test fun badMac() {
        assertNull(WakeOnLan.packet(""))
        assertNull(WakeOnLan.packet("a8:23:fe:01:02"))
        assertNull(WakeOnLan.packet("a8:23:fe:01:02:zz"))
    }

    @Test fun broadcast() {
        fun b(ip: String, prefix: Int) = WakeOnLan.broadcast(InetAddress.getByName(ip) as Inet4Address, prefix).hostAddress
        assertEquals("192.168.1.255", b("192.168.1.37", 24))
        assertEquals("10.0.255.255", b("10.0.3.4", 16))
        assertEquals("172.16.0.7", b("172.16.0.5", 30))
    }
}
