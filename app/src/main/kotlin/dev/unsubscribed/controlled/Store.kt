package dev.unsubscribed.controlled

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The paired TV: everything the app keeps, in one file. [clientKey] skips the Accept prompt, [pin] is the TV's certificate
 * (SPKI SHA-256, trust on first use, null on the ws:// fallback), [macs] are its Wi-Fi and wired addresses for Wake-on-LAN.
 */
@Serializable
data class PairedTv(val ip: String, val name: String, val clientKey: String, val pin: String?, val macs: List<String> = emptyList())

/** One TV in v1. In noBackupFilesDir: the key only works on this home Wi-Fi, so it never leaves the phone. */
class Store(context: Context) {
    private val file = File(context.noBackupFilesDir, "tv.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PairedTv? = runCatching { json.decodeFromString<PairedTv>(file.readText()) }.getOrNull()

    fun save(tv: PairedTv) {
        // Write then rename, so a crash mid-write never leaves half a file.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(tv))
        tmp.renameTo(file)
    }

    fun forget() {
        file.delete()
    }
}
