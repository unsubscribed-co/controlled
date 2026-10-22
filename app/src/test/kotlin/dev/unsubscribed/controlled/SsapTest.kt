package dev.unsubscribed.controlled

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsapTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun registerWithoutClientKey() {
        val jsonStr = Ssap.register(null)
        val root = json.decodeFromString<JsonObject>(jsonStr)

        assertEquals("register_0", root["id"]?.jsonPrimitive?.content)
        assertEquals("register", root["type"]?.jsonPrimitive?.content)

        val payload = root["payload"]?.jsonObject
        assertNotNull(payload)
        assertEquals(false, payload!!["forcePairing"]?.jsonPrimitive?.boolean)
        assertEquals("PROMPT", payload["pairingType"]?.jsonPrimitive?.content)
        assertNull(payload["client-key"])

        val manifest = payload["manifest"]?.jsonObject
        assertNotNull(manifest)
        assertEquals(1, manifest!!["manifestVersion"]?.jsonPrimitive?.int)
        assertEquals("1.1", manifest["appVersion"]?.jsonPrimitive?.content)

        val perms = manifest["permissions"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(Ssap.PERMISSIONS, perms)
    }

    @Test
    fun registerWithClientKey() {
        val key = "secret-client-key-xyz"
        val jsonStr = Ssap.register(key)
        val root = json.decodeFromString<JsonObject>(jsonStr)

        assertEquals("register_0", root["id"]?.jsonPrimitive?.content)
        assertEquals("register", root["type"]?.jsonPrimitive?.content)

        val payload = root["payload"]?.jsonObject
        assertNotNull(payload)
        assertEquals(key, payload!!["client-key"]?.jsonPrimitive?.content)
        assertEquals(false, payload["forcePairing"]?.jsonPrimitive?.boolean)
        assertEquals("PROMPT", payload["pairingType"]?.jsonPrimitive?.content)
    }

    @Test
    fun helloMessageFormat() {
        val jsonStr = Ssap.hello()
        val root = json.decodeFromString<JsonObject>(jsonStr)

        assertEquals("hello", root["id"]?.jsonPrimitive?.content)
        assertEquals("hello", root["type"]?.jsonPrimitive?.content)
        assertTrue(root["payload"]?.jsonObject?.isEmpty() == true)
    }

    @Test
    fun requestIdTypeAndUriPrefixing() {
        val payload = buildJsonObject { put("volume", 15) }
        val raw = Ssap.request("7", "audio/setVolume", payload)
        val root = json.decodeFromString<JsonObject>(raw)

        assertEquals("7", root["id"]?.jsonPrimitive?.content)
        assertEquals("request", root["type"]?.jsonPrimitive?.content)
        assertEquals("ssap://audio/setVolume", root["uri"]?.jsonPrimitive?.content)
        assertEquals(15, root["payload"]?.jsonObject?.get("volume")?.jsonPrimitive?.int)

        val withExistingPrefix = Ssap.request("8", "ssap://system/turnOff", JsonObject(emptyMap()))
        val rootWithPrefix = json.decodeFromString<JsonObject>(withExistingPrefix)
        assertEquals("ssap://system/turnOff", rootWithPrefix["uri"]?.jsonPrimitive?.content)

        val subscribeMsg = Ssap.request("9", "audio/getVolume", JsonObject(emptyMap()), type = "subscribe")
        val rootSubscribe = json.decodeFromString<JsonObject>(subscribeMsg)
        assertEquals("subscribe", rootSubscribe["type"]?.jsonPrimitive?.content)
        assertEquals("ssap://audio/getVolume", rootSubscribe["uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun securePointerUrlRewriting() {
        val wsUrl = "ws://192.168.1.12:3000/resources/token123/netinput.pointer.sock"
        val expected = "wss://192.168.1.12:3001/resources/token123/netinput.pointer.sock"
        assertEquals(expected, Ssap.securePointerUrl(wsUrl))

        assertEquals(expected, Ssap.securePointerUrl(expected))
        assertEquals("garbage", Ssap.securePointerUrl("garbage"))
        assertEquals("http://192.168.1.12:3000/path", Ssap.securePointerUrl("http://192.168.1.12:3000/path"))
        assertEquals("ws://192.168.1.12:8080/path", Ssap.securePointerUrl("ws://192.168.1.12:8080/path"))
    }
}
