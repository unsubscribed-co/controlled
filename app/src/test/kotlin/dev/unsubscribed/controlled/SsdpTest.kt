package dev.unsubscribed.controlled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpTest {

    @Test
    fun searchMessageFormat() {
        val message = Ssdp.searchMessage()
        assertTrue("Must start with M-SEARCH header", message.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue("Must contain HOST", message.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue("Must contain MAN", message.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue("Must contain MX: 2", message.contains("MX: 2\r\n"))
        assertTrue("Must contain SEARCH_TARGET", message.contains("ST: ${Ssdp.SEARCH_TARGET}\r\n"))
        assertTrue("Must end with empty line CRLF", message.endsWith("\r\n\r\n"))
    }

    @Test
    fun parseAnswerGoodAnswer() {
        val datagram = "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: http://192.168.1.12:1137/\r\n" +
            "ST: urn:lge-com:service:webos-second-screen:1\r\n" +
            "USN: uuid:0b2b1a58-1234::urn:lge-com:service:webos-second-screen:1\r\n\r\n"

        val headers = Ssdp.parseAnswer(datagram)
        assertNotNull(headers)
        assertEquals("http://192.168.1.12:1137/", headers?.get("LOCATION"))
        assertEquals("urn:lge-com:service:webos-second-screen:1", headers?.get("ST"))
        assertEquals("uuid:0b2b1a58-1234::urn:lge-com:service:webos-second-screen:1", headers?.get("USN"))
        assertEquals("max-age=1800", headers?.get("CACHE-CONTROL"))
    }

    @Test
    fun parseAnswerNotifyReturnsNull() {
        val notifyDatagram = "NOTIFY * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "LOCATION: http://192.168.1.12:1137/\r\n" +
            "NT: urn:lge-com:service:webos-second-screen:1\r\n" +
            "NTS: ssdp:alive\r\n\r\n"

        val headers = Ssdp.parseAnswer(notifyDatagram)
        assertNull(headers)
    }

    @Test
    fun parseAnswerLowercaseHeaders() {
        val datagram = "HTTP/1.1 200 OK\r\n" +
            "location: http://192.168.1.15:1137/\r\n" +
            "st: urn:lge-com:service:webos-second-screen:1\r\n" +
            "usn: uuid:test-usn\r\n\r\n"

        val headers = Ssdp.parseAnswer(datagram)
        assertNotNull(headers)
        assertEquals("http://192.168.1.15:1137/", headers?.get("LOCATION"))
        assertEquals("urn:lge-com:service:webos-second-screen:1", headers?.get("ST"))
        assertEquals("uuid:test-usn", headers?.get("USN"))
    }

    @Test
    fun parseDescriptionWithFields() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>[LG] webOS TV UP7500PSC</friendlyName>
                    <manufacturer>LG Electronics</manufacturer>
                    <modelNumber>70UP7500PSC</modelNumber>
                    <UDN>uuid:0b2b1a58</UDN>
                </device>
            </root>
        """.trimIndent()

        val (friendlyName, modelNumber) = Ssdp.parseDescription(xml)
        assertEquals("[LG] webOS TV UP7500PSC", friendlyName)
        assertEquals("70UP7500PSC", modelNumber)
    }

    @Test
    fun parseDescriptionWithoutFields() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                </device>
            </root>
        """.trimIndent()

        val (friendlyName, modelNumber) = Ssdp.parseDescription(xml)
        assertNull(friendlyName)
        assertNull(modelNumber)
    }

    @Test
    fun parseDescriptionGarbageInput() {
        val garbageInputs = listOf(
            "",
            "not xml",
            "<html><body><friendlyName>Unclosed tag",
            "<<<>>>",
            "{\"status\": 200}"
        )
        for (garbage in garbageInputs) {
            val (friendlyName, modelNumber) = Ssdp.parseDescription(garbage)
            assertNull("Expected null friendlyName for: $garbage", friendlyName)
            assertNull("Expected null modelNumber for: $garbage", modelNumber)
        }
    }

    @Test
    fun displayNameFormatting() {
        assertEquals("webOS TV UP7500PSC", Ssdp.displayName("[LG] webOS TV UP7500PSC"))
        assertEquals("webOS TV", Ssdp.displayName("webOS TV"))
        assertEquals("Bedroom TV", Ssdp.displayName("[LG] Bedroom TV"))
        assertNull(Ssdp.displayName(null))
        assertNull(Ssdp.displayName(""))
        assertNull(Ssdp.displayName("   "))
        assertNull(Ssdp.displayName("[LG] "))
    }
}
