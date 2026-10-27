package dev.unsubscribed.controlled

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTest {
    @Test fun aV1FileStillLoads() {
        val v1 = """{"ip":"192.168.1.12","name":"LG TV","clientKey":"k","pin":"p","macs":["a8:23:fe:01:02:0f"]}"""
        val tv = Json { ignoreUnknownKeys = true }.decodeFromString<PairedTv>(v1)
        assertEquals("192.168.1.12", tv.ip)
        assertEquals(emptyList<String>(), tv.pins)
        assertEquals(emptyMap<String, Int>(), tv.uses)
    }
}

class ControllersTest {
    private val tv = PairedTv("192.168.1.12", "LG TV", "k", "p")
    private val pc = PairedPc("AA:BB:CC:DD:EE:FF", "Gaming PC")

    @Test fun theDefaultIsTheOneMarkedElseTheFirst() {
        assertEquals(pc, Controllers(listOf(tv), listOf(pc), default = pc.id).defaultOne())
        assertEquals(tv, Controllers(listOf(tv), listOf(pc), default = "gone").defaultOne())
        assertEquals(null, Controllers().defaultOne())
    }

    @Test fun aSavedListRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val all = Controllers(listOf(tv), listOf(pc), default = tv.id)
        assertEquals(all, json.decodeFromString<Controllers>(json.encodeToString(all)))
        // Ids aren't stored: they follow from the key and the address.
        assertEquals(false, json.encodeToString(all).contains("\"id\""))
    }
}
