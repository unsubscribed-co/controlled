package dev.unsubscribed.controlled

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Something the app controls: a TV or a PC. [id] is stable across renames and IP changes. */
sealed interface Controller {
    val name: String
    val id: String
}

/**
 * A paired TV. [clientKey] skips the Accept prompt, [pin] is the TV's certificate
 * (SPKI SHA-256, trust on first use, null on the ws:// fallback), [macs] are its Wi-Fi and wired addresses for Wake-on-LAN.
 * [name] is what the person calls it (renameable). [pins] are app ids in the order pinned; [uses] counts launches per app id.
 * [bleKey] is the 8-byte key (16 hex digits) that lets Turn on reach the TV over Bluetooth from any sleep (BleWake.kt).
 * New fields have defaults, so files from older versions still load.
 */
@Serializable
data class PairedTv(
    val ip: String,
    override val name: String,
    val clientKey: String,
    val pin: String?,
    val macs: List<String> = emptyList(),
    val pins: List<String> = emptyList(),
    val uses: Map<String, Int> = emptyMap(),
    val bleKey: String? = null,
) : Controller {
    // TVs are told apart by their key: their IP can change.
    override val id get() = "tv:$clientKey"
}

/** A computer the phone types into and points at as a Bluetooth keyboard and mouse (PcHid.kt), by its Bluetooth address. */
@Serializable
data class PairedPc(val address: String, override val name: String) : Controller {
    override val id get() = "pc:$address"
}

/** Every controller set up on this phone, and the id of the one the app opens on. */
@Serializable
data class Controllers(val tvs: List<PairedTv> = emptyList(), val pcs: List<PairedPc> = emptyList(), val default: String? = null) {
    val isEmpty get() = tvs.isEmpty() && pcs.isEmpty()
    val all: List<Controller> get() = tvs + pcs
    fun defaultOne(): Controller? = all.firstOrNull { it.id == default } ?: all.firstOrNull()
}

/** In noBackupFilesDir: the keys only work on this home Wi-Fi and this phone, so they never leave it. */
class Store(context: Context) {
    private val file = File(context.noBackupFilesDir, "controllers.json")
    /** Versions up to 1.4 kept one TV here; it becomes the first controller, and the default (the file stays, unread). */
    private val v1 = File(context.noBackupFilesDir, "tv.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Controllers {
        runCatching { return json.decodeFromString<Controllers>(file.readText()) }
        val tv = runCatching { json.decodeFromString<PairedTv>(v1.readText()) }.getOrNull() ?: return Controllers()
        return Controllers(tvs = listOf(tv), default = tv.id).also(::save)
    }

    fun save(controllers: Controllers) {
        // Write then rename, so a crash mid-write never leaves half a file.
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(controllers))
        tmp.renameTo(file)
    }
}
