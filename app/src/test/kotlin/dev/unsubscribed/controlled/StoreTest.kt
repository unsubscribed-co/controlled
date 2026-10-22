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
