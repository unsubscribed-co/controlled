package dev.unsubscribed.controlled

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/** A one-tap row entry: an app ([id] is its appId) or an input ([id] is its input id). */
data class Shortcut(val id: String, val label: String, val isInput: Boolean)

/** Where the app is: finding a TV, waiting for Accept, or the remote for the paired one. */
sealed interface Screen {
    data object Find : Screen
    data class Pairing(val tv: FoundTv) : Screen
    data object Remote : Screen
}

/** The remote's link to the paired TV. */
enum class Link { Connecting, Connected, Waking, Off, CertificateChanged }

/**
 * The whole app's state. The socket lives only while the app is on screen ([start]/[stop] from the activity): no service,
 * no background work. Every press is fire-and-forget; a press while not connected starts a reconnect instead.
 */
class RemoteModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient()

    var tv by mutableStateOf(store.load())
        private set
    var screen by mutableStateOf<Screen>(if (tv == null) Screen.Find else Screen.Remote)
        private set
    var link by mutableStateOf(Link.Connecting)
        private set
    var volume by mutableStateOf<Int?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    /** Pairing failed or the typed address didn't answer: shown on the Find screen. */
    var findError by mutableStateOf<FindError?>(null)
        private set
    val found = mutableStateListOf<FoundTv>()
    var searching by mutableStateOf(false)
        private set
    val shortcuts = mutableStateListOf<Shortcut>()

    private var client: TvClient? = null
    private var pointer: PointerSocket? = null
    private var session: Job? = null
    private var search: Job? = null

    /** The activity is on screen: connect to the paired TV, or search for one. */
    fun start() {
        if (screen == Screen.Find) search() else if (tv != null && session?.isActive != true) connect()
    }

    /** The activity left the screen: close everything. */
    fun stop() {
        search?.cancel()
        session?.cancel()
        disconnect()
    }

    fun search() {
        search?.cancel()
        found.clear()
        search = viewModelScope.launch {
            searching = true
            try {
                Ssdp.discover(getApplication()).collect { if (found.none { f -> f.ip == it.ip }) found += it }
            } finally {
                searching = false
            }
        }
    }

    /** Pairs with [found] (from the search, or typed by hand with a placeholder name). */
    fun pair(found: FoundTv) {
        search?.cancel()
        findError = null
        session?.cancel()
        disconnect()
        session = viewModelScope.launch {
            val c = TvClient(found.ip, http)
            try {
                val pairing = c.connect(clientKey = null, pin = null) { screen = Screen.Pairing(found) }
                val paired = PairedTv(found.ip, found.name, pairing.clientKey, pairing.pin, macs(c))
                store.save(paired)
                tv = paired
                screen = Screen.Remote
                run(c)
            } catch (e: TvException) {
                c.close()
                findError = when (e.kind) {
                    TvException.Kind.Rejected -> FindError.Rejected
                    TvException.Kind.PairingTimeout -> FindError.NoAnswer
                    else -> FindError.Unreachable
                }
                screen = Screen.Find
            }
        }
    }

    fun cancelPairing() {
        session?.cancel()
        disconnect()
        screen = Screen.Find
        search()
    }

    /** Forget and re-pair: the only way past a changed certificate. */
    fun forget() {
        session?.cancel()
        disconnect()
        store.forget()
        tv = null
        shortcuts.clear()
        screen = Screen.Find
        search()
    }

    fun connect() {
        val paired = tv ?: return
        session?.cancel()
        disconnect()
        session = viewModelScope.launch {
            link = Link.Connecting
            val c = TvClient(paired.ip, http)
            try {
                c.connect(paired.clientKey, paired.pin)
                run(c)
            } catch (e: TvException) {
                c.close()
                link = if (e.kind == TvException.Kind.CertificateChanged) Link.CertificateChanged else Link.Off
            }
        }
    }

    /**
     * Power on: the magic packet, then a connect attempt every second and the packet again every 10 s, for about 20 s
     * (measured on Juan's TV: the session was ready 4.6 s after the packet).
     */
    fun powerOn() {
        val paired = tv ?: return
        session?.cancel()
        disconnect()
        session = viewModelScope.launch {
            link = Link.Waking
            val started = System.currentTimeMillis()
            var lastPacket = 0L
            while (System.currentTimeMillis() - started < 20_000) {
                if (System.currentTimeMillis() - lastPacket >= 10_000) {
                    WakeOnLan.send(getApplication(), paired.macs)
                    lastPacket = System.currentTimeMillis()
                }
                val c = TvClient(paired.ip, http)
                try {
                    c.connect(paired.clientKey, paired.pin)
                    run(c)
                    return@launch
                } catch (e: TvException) {
                    c.close()
                    if (e.kind == TvException.Kind.CertificateChanged) {
                        link = Link.CertificateChanged
                        return@launch
                    }
                }
                delay(1_000)
            }
            link = Link.Off
        }
    }

    /** Connected: open the pointer socket, read the rows and the volume, then wait until the socket closes. */
    private suspend fun run(c: TvClient) {
        client = c
        pointer = runCatching { c.pointer() }.getOrNull()
        link = Link.Connected
        viewModelScope.launch { loadShortcuts(c) }
        val audio = viewModelScope.launch {
            runCatching {
                c.subscribe("audio/getVolume").collect { p ->
                    val status = p["volumeStatus"]?.jsonObject ?: p
                    status["volume"]?.jsonPrimitive?.intOrNull?.let { volume = it }
                    (status["muteStatus"] ?: status["muted"])?.jsonPrimitive?.booleanOrNull?.let { muted = it }
                }
            }
        }
        try {
            c.closed.first { it }
        } finally {
            audio.cancel()
            // A newer session may already own the link: only the current one reports it closed.
            if (client === c) {
                disconnect()
                link = Link.Off
            }
        }
    }

    private suspend fun loadShortcuts(c: TvClient) {
        val apps = runCatching { c.request("com.webos.applicationManager/listLaunchPoints") }.getOrNull()
            ?.get("launchPoints")?.jsonArray.orEmpty().mapNotNull {
                val o = it.jsonObject
                val id = o.string("id") ?: return@mapNotNull null
                Shortcut(id, o.string("title") ?: id, isInput = false)
            }
        // Connected inputs first: on Juan's TV HDMI 1 is empty and HDMI 2 is the PC.
        val inputs = runCatching { c.request("tv/getExternalInputList") }.getOrNull()
            ?.get("devices")?.jsonArray.orEmpty().map { it.jsonObject }
            .sortedByDescending { it["connected"]?.jsonPrimitive?.booleanOrNull == true }
            .mapNotNull { o ->
                val id = o.string("id") ?: return@mapNotNull null
                Shortcut(id, o.string("label") ?: id, isInput = true)
            }
        shortcuts.clear()
        shortcuts += inputs + apps
    }

    /** Wake-on-LAN targets: every MAC the TV reports (which interface is live isn't said, so both). */
    private suspend fun macs(c: TvClient): List<String> {
        val info = runCatching { c.request("com.webos.service.connectionmanager/getinfo") }.getOrNull() ?: return emptyList()
        return listOf("wifiInfo", "wiredInfo").mapNotNull { info[it]?.jsonObject?.string("macAddress") }.filter { it.isNotBlank() }
    }

    private fun disconnect() {
        pointer?.close()
        pointer = null
        client?.close()
        client = null
    }

    // Presses. Each returns quietly when there's no socket: the screen shows the link state and how to reconnect.

    fun button(name: String) {
        if (pointer?.button(name) != true) reconnect()
    }

    fun request(uri: String, payload: JsonObject = JsonObject(emptyMap())) {
        val c = client ?: return reconnect()
        viewModelScope.launch { runCatching { c.request(uri, payload) } }
    }

    fun volumeUp() = request("audio/volumeUp")
    fun volumeDown() = request("audio/volumeDown")
    fun toggleMute() = request("audio/setMute", buildJsonObject { put("mute", !muted) })
    fun channelUp() = request("tv/channelUp")
    fun channelDown() = request("tv/channelDown")
    fun play() = request("media.controls/play")
    fun pause() = request("media.controls/pause")

    // The TV answers turnOff unreliably as it shuts down, so don't wait for it.
    fun powerOff() {
        client?.fire("system/turnOff") ?: return
    }

    fun open(s: Shortcut) =
        if (s.isInput) request("tv/switchInput", buildJsonObject { put("inputId", s.id) })
        else request("system.launcher/launch", buildJsonObject { put("id", s.id) })

    fun type(text: String) = request("com.webos.service.ime/insertText", buildJsonObject { put("text", text); put("replace", 0) })
    fun enter() = request("com.webos.service.ime/sendEnterKey")
    fun delete() = request("com.webos.service.ime/deleteCharacters", buildJsonObject { put("count", 1) })

    fun move(dx: Int, dy: Int) {
        pointer?.move(dx, dy)
    }

    fun click() {
        if (pointer?.click() != true) reconnect()
    }

    fun scroll(dx: Int, dy: Int) {
        pointer?.scroll(dx, dy)
    }

    /** A press found no socket: reconnect, unless that's already under way or needs the person (a changed certificate). */
    private fun reconnect() {
        if (link == Link.Off || link == Link.Connected) connect()
    }

    override fun onCleared() = stop()
}

/** Why pairing didn't happen, for the Find screen. */
enum class FindError { Unreachable, Rejected, NoAnswer }

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
