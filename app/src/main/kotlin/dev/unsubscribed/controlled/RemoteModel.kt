package dev.unsubscribed.controlled

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

/** Where the app is: finding a TV, waiting for Accept, the TV remote, or the PC keyboard and trackpad. */
sealed interface Screen {
    data object Find : Screen
    data class Pairing(val tv: FoundTv) : Screen
    data object Remote : Screen
    data object Pc : Screen
}

/** The remote's link to the paired TV. */
enum class Link { Connecting, Connected, Waking, Off, NoWifi, CertificateChanged }

/** Why pairing didn't happen, for the Find screen. */
enum class FindError { Unreachable, Rejected, NoAnswer }

/** A press made while the socket was down: sent on reconnect if it's still fresh (see [PressQueue]). */
private typealias Press = (TvClient, PointerSocket?) -> Unit

/** Presses made while reconnecting: at most [max], none older than [maxAgeMs], so nothing is ever replayed late. */
class PressQueue<T>(private val max: Int = 5, private val maxAgeMs: Long = 1_000) {
    private val items = ArrayDeque<Pair<Long, T>>()

    fun add(now: Long, item: T) {
        items.addLast(now to item)
        while (items.size > max) items.removeFirst()
    }

    fun drain(now: Long): List<T> = items.filter { now - it.first <= maxAgeMs }.map { it.second }.also { items.clear() }
}

/** Waits between reconnect attempts while the app is on screen: fast at first, then every 5 s. */
fun retryDelay(attempt: Int): Long = listOf(0L, 300L, 1_000L, 2_000L, 4_000L).getOrElse(attempt) { 5_000L }

/**
 * The whole app's state. The socket lives while the app is on screen and for [GRACE_MS] after it leaves, so locking the
 * phone or a quick app switch comes back to a live socket; after that it closes: no service, no background work.
 */
class RemoteModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    private val http = OkHttpClient()
    private val cm = app.getSystemService(ConnectivityManager::class.java)

    /** Every controller set up, and the default one; the app opens on the default. */
    var controllers by mutableStateOf(store.load())
        private set
    /** The TV on screen, when the current controller is a TV. */
    var tv by mutableStateOf(controllers.defaultOne() as? PairedTv)
        private set
    /** The PC on screen, when the current controller is a PC. */
    var pc by mutableStateOf(controllers.defaultOne() as? PairedPc)
        private set
    var screen by mutableStateOf(screenFor(controllers.defaultOne()))
        private set
    /**
     * The phone as a Bluetooth keyboard and mouse; a computer that connects while adding one is saved as a PC. Null before
     * Android 9, which has no HID device profile: there the app doesn't offer Add a PC.
     */
    val hid = if (Build.VERSION.SDK_INT >= 28) PcHid(app) { address, name -> if (screen == Screen.Pc && pc == null) savePc(PairedPc(address, name ?: "PC", Layout.guess())) } else null
    var link by mutableStateOf(Link.Connecting)
        private set
    var volume by mutableStateOf<Int?>(null)
        private set
    var muted by mutableStateOf(false)
        private set
    /** The foreground app's id on the TV (what's on now). */
    var foreground by mutableStateOf<String?>(null)
        private set
    /**
     * The TV's own power state (getPowerState), null until known. With Quick Start+ an LG keeps its socket open in standby
     * ("Active Standby", "Suspend"), so connected doesn't mean on; "Screen Off" is on with the picture off (aiowebostv's reading).
     */
    var powerState by mutableStateOf<String?>(null)
        private set
    /** The last Turn on timed out: the TV likely ignores Wake-on-LAN after a while off unless its setting is on. */
    var wakeFailed by mutableStateOf(false)
        private set
    /** Connected, but the TV is in standby or its screen is off: the remote offers Turn on. */
    val asleep get() = powerState in ASLEEP
    /** The TV's own keyboard is up (a text field has focus there): the Type sheet opens by itself. */
    var tvKeyboard by mutableStateOf(false)
        private set
    var findError by mutableStateOf<FindError?>(null)
        private set
    val found = mutableStateListOf<FoundTv>()
    var searching by mutableStateOf(false)
        private set
    val apps = mutableStateListOf<TvApp>()
    val inputs = mutableStateListOf<TvInput>()
    /** App icons by app id, kept across reconnects (the URLs change every connection, the pictures don't). */
    val icons = mutableStateMapOf<String, ImageBitmap>()

    private var client: TvClient? = null
    private var pointer: PointerSocket? = null
    private var session: Job? = null
    private var search: Job? = null
    private var grace: Job? = null
    private var visible = false
    private val queue = PressQueue<Press>()
    /** A link shared from the phone, kept until the TV is connected (no 1 s limit: the person asked for it). */
    private var pendingShare: TvLaunch? = null

    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { if (visible && link == Link.NoWifi || link == Link.Off) connect() }
        }

        override fun onLost(network: Network) {
            viewModelScope.launch {
                if (!hasWifi()) {
                    session?.cancel()
                    disconnect()
                    link = Link.NoWifi
                }
            }
        }
    }

    init {
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
    }

    private fun hasWifi() = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }

    /** The activity is on screen: back to a live socket if the grace kept one (checked with a quick probe), else connect. */
    fun start() {
        visible = true
        grace?.cancel()
        if (screen == Screen.Pc) {
            pc?.let { hid?.layout = it.layout }
            return hid?.start(pc?.address) ?: Unit
        }
        if (screen == Screen.Find) return search()
        if (tv == null) return
        val c = client
        if (c != null && link == Link.Connected) viewModelScope.launch {
            // Android may have frozen the process in the background: a socket that doesn't answer in 700 ms is gone.
            val alive = withTimeoutOrNull(700) { runCatching { c.request("audio/getVolume") }.isSuccess } == true
            log { "resume probe: alive=$alive" }
            if (!alive && client === c) connect()
        } else if (session?.isActive != true) connect()
    }

    /** The activity left the screen: keep the socket for [GRACE_MS], then close everything. */
    fun stop() {
        visible = false
        search?.cancel()
        grace?.cancel()
        grace = viewModelScope.launch {
            delay(GRACE_MS)
            session?.cancel()
            disconnect()
            link = Link.Connecting
            hid?.stop()
        }
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
                save(paired)
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
        tv?.let(::remove)
    }

    /** Opens [controller]: the TV remote or the PC pad; null finds a TV. Only the one on screen holds a connection. */
    fun select(controller: Controller?) {
        session?.cancel()
        disconnect()
        search?.cancel()
        link = Link.Connecting
        if (controller !is PairedPc) hid?.stop()
        if (controller !is PairedTv || controller.id != tv?.id) {
            apps.clear()
            inputs.clear()
            icons.clear()
            volume = null
            foreground = null
        }
        tv = controller as? PairedTv
        pc = controller as? PairedPc
        screen = screenFor(controller)
        if (visible) start()
    }

    /** Find and pair another TV; Back returns to the default controller. */
    fun addTv() = select(null)

    /** Waits for a computer to pair with the phone (from its Bluetooth settings); it's saved when it connects. */
    fun addPc() {
        select(null)
        search?.cancel()
        screen = Screen.Pc
        if (visible) hid?.start(null)
    }

    /** Back from adding a controller, to the default one (or to finding a TV when there's none yet). */
    fun cancelAdd() = select(controllers.defaultOne())

    fun setDefault(controller: Controller) = saveAll(controllers.copy(default = controller.id))

    fun remove(controller: Controller) {
        val rest = controllers.copy(tvs = controllers.tvs.filter { it.id != controller.id }, pcs = controllers.pcs.filter { it.id != controller.id })
        // Removing the default makes the next one the default.
        saveAll(if (rest.default == controller.id) rest.copy(default = rest.all.firstOrNull()?.id) else rest)
        if (controller.id == tv?.id || controller.id == pc?.id) select(controllers.defaultOne())
    }

    fun renameController(controller: Controller, name: String) {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return
        when (controller) {
            is PairedTv -> save(controller.copy(name = clean))
            is PairedPc -> savePc(controller.copy(name = clean))
        }
    }

    fun setLayout(controller: PairedPc, layout: Layout) {
        savePc(controller.copy(layout = layout))
        if (pc?.id == controller.id) hid?.layout = layout
    }

    private fun screenFor(controller: Controller?): Screen = when (controller) {
        is PairedTv -> Screen.Remote
        is PairedPc -> Screen.Pc
        null -> Screen.Find
    }

    /** Saves the Bluetooth wake key, or clears it when blank; false if it isn't 16 hex digits. */
    fun setBleKey(key: String): Boolean {
        val paired = tv ?: return false
        val clean = key.filter { !it.isWhitespace() && it != ':' }.lowercase()
        if (clean.isNotEmpty() && (clean.length != 16 || BleWake.hex(clean) == null)) return false
        save(paired.copy(bleKey = clean.ifEmpty { null }))
        return true
    }

    fun rename(name: String) {
        tv?.let { renameController(it, name) }
    }

    fun togglePin(id: String) {
        val paired = tv ?: return
        save(paired.copy(pins = if (id in paired.pins) paired.pins - id else paired.pins + id))
    }

    /** Takes an app out of Most used by forgetting its launches; returns the count, for Undo. */
    fun forgetUses(id: String): Int {
        val paired = tv ?: return 0
        val count = paired.uses[id] ?: 0
        save(paired.copy(uses = paired.uses - id))
        return count
    }

    fun restoreUses(id: String, count: Int) {
        val paired = tv ?: return
        if (count > 0) save(paired.copy(uses = paired.uses + (id to count)))
    }

    /** Moves a pinned app [by] places (negative: earlier), the accessible alternative to dragging. */
    fun movePin(id: String, by: Int) {
        val paired = tv ?: return
        val pins = paired.pins.toMutableList()
        val from = pins.indexOf(id).takeIf { it >= 0 } ?: return
        val to = (from + by).coerceIn(0, pins.lastIndex)
        pins.add(to, pins.removeAt(from))
        save(paired.copy(pins = pins))
    }

    /** Saves [paired] in its place (a TV re-paired at the same address replaces its old entry); the first one is the default. */
    private fun save(paired: PairedTv) {
        val tvs = controllers.tvs.toMutableList()
        val i = tvs.indexOfFirst { it.id == paired.id || it.ip == paired.ip }
        if (i >= 0) tvs[i] = paired else tvs += paired
        val old = controllers.tvs.getOrNull(i)?.id
        val default = if (controllers.default == null || controllers.default == old) paired.id else controllers.default
        saveAll(controllers.copy(tvs = tvs, default = default))
        if (tv == null || tv?.id == old) tv = paired
    }

    private fun savePc(paired: PairedPc) {
        val pcs = controllers.pcs.toMutableList()
        val i = pcs.indexOfFirst { it.id == paired.id }
        if (i >= 0) pcs[i] = paired else pcs += paired
        saveAll(controllers.copy(pcs = pcs, default = controllers.default ?: paired.id))
        if (pc == null || pc?.id == paired.id) pc = paired
    }

    private fun saveAll(all: Controllers) {
        store.save(all)
        controllers = all
    }

    /**
     * Connects to the paired TV and keeps it connected while the app is on screen: after a drop or a failed attempt it
     * tries again on [retryDelay]'s schedule, and once looks for the TV at a new address (a DHCP change).
     */
    fun connect() {
        val paired = tv ?: return
        if (!hasWifi()) {
            link = Link.NoWifi
            return
        }
        session?.cancel()
        disconnect()
        session = viewModelScope.launch {
            var attempt = 0
            var searched = false
            if (link != Link.Off) link = Link.Connecting
            while (true) {
                val current = tv ?: return@launch
                val c = TvClient(current.ip, http)
                try {
                    c.connect(current.clientKey, current.pin)
                    attempt = 0
                    run(c)
                } catch (e: TvException) {
                    c.close()
                    if (e.kind == TvException.Kind.CertificateChanged) {
                        link = Link.CertificateChanged
                        return@launch
                    }
                    if (attempt >= 1) link = Link.Off
                    if (attempt >= 2 && !searched) {
                        searched = true
                        if (refind(current)) continue
                    }
                }
                if (!visible) return@launch
                delay(retryDelay(attempt++))
            }
        }
        if (paired.macs.isEmpty()) log { "no MACs stored: power on unavailable" }
    }

    /** The TV may have a new address: try every LG TV the search finds with our key; the one that accepts it is ours. */
    private suspend fun refind(current: PairedTv): Boolean {
        val candidates = withTimeoutOrNull(3_500) { Ssdp.discover(getApplication(), 3_000).take(8).toList() }
            ?: return false
        for (f in candidates.filter { it.ip != current.ip }) {
            val c = TvClient(f.ip, http)
            val ok = runCatching { c.connect(current.clientKey, current.pin) }.isSuccess
            c.close()
            if (ok) {
                log { "TV moved: ${current.ip} -> ${f.ip}" }
                save(current.copy(ip = f.ip))
                return true
            }
        }
        return false
    }

    /**
     * Power on: the magic packet, then a connect attempt every second and the packet again every 10 s, for about 20 s
     * (measured on Juan's TV: the session was ready 4.6 s after the packet). Then the normal reconnect loop takes over.
     */
    fun powerOn() {
        val paired = tv ?: return
        bleWake(paired)
        irWake(paired)
        if (link == Link.Waking) return // Already waking: another press only fires the IR and Bluetooth shots again.
        val c = client
        if (c != null && link == Link.Connected) {
            // The socket is up but the TV is in standby: wake it over SSAP, as aiowebostv does; the packet is a backup.
            viewModelScope.launch {
                WakeOnLan.send(getApplication(), paired.macs, paired.ip)
                val uri = if (powerState == "Screen Off") "com.webos.service.tvpower/power/turnOnScreen" else "system/turnOn"
                runCatching { c.request(uri) }.onSuccess { log { "$uri: $it" } }.onFailure { log { "$uri failed: $it" } }
            }
            return
        }
        session?.cancel()
        disconnect()
        session = viewModelScope.launch {
            link = Link.Waking
            wakeFailed = false
            val started = System.currentTimeMillis()
            var lastPacket = 0L
            while (System.currentTimeMillis() - started < 20_000) {
                if (System.currentTimeMillis() - lastPacket >= 10_000) {
                    WakeOnLan.send(getApplication(), paired.macs, paired.ip)
                    lastPacket = System.currentTimeMillis()
                }
                val c = TvClient(paired.ip, http)
                try {
                    c.connect(paired.clientKey, paired.pin)
                    run(c)
                    break
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
            wakeFailed = client == null
            if (visible) connect()
        }
    }

    /**
     * With a key, Turn on also goes over Bluetooth: it reaches the TV from any sleep, while Wake-on-LAN stops working about
     * an hour after the TV turns off (docs/research/lg-power-on.md).
     */
    private fun bleWake(paired: PairedTv) {
        val key = paired.bleKey?.let(BleWake::hex) ?: return
        val tv = paired.macs.firstOrNull()?.let(BleWake::bluetoothAddress) ?: return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) return
        log { "ble wake sent=${BleWake.send(app, key, tv)}" }
    }

    /**
     * Second to Bluetooth: when no wake key is set and the phone has an IR blaster, Turn on also sends LG's IR power
     * code, which reaches the TV from any state like its own remote — but only line of sight, so aim the phone (IrWake.kt).
     */
    private fun irWake(paired: PairedTv) {
        if (paired.bleKey?.let(BleWake::hex) != null) return // Bluetooth wake is set up and is hands-off; prefer it.
        val app = getApplication<Application>()
        if (IrWake.hasEmitter(app)) log { "ir power sent=${IrWake.powerOn(app)}" }
    }

    /** Connected: flush fresh queued presses, open the pointer socket, read state, then wait until the socket closes. */
    private suspend fun run(c: TvClient) {
        client = c
        link = Link.Connected
        val p = runCatching { c.pointer() }.getOrNull()
        pointer = p
        queue.drain(System.currentTimeMillis()).forEach { it(c, p) }
        sendShare(c)
        val watchers = listOf(
            viewModelScope.launch { loadApps(c) },
            viewModelScope.launch {
                runCatching {
                    c.subscribe("audio/getVolume").collect { payload ->
                        val status = payload["volumeStatus"]?.jsonObject ?: payload
                        status["volume"]?.jsonPrimitive?.intOrNull?.let { volume = it }
                        (status["muteStatus"] ?: status["muted"])?.jsonPrimitive?.booleanOrNull?.let { muted = it }
                    }
                }
            },
            viewModelScope.launch {
                runCatching {
                    c.subscribe("com.webos.applicationManager/getForegroundAppInfo").collect { foreground = it.string("appId") }
                }
            },
            viewModelScope.launch {
                runCatching {
                    c.subscribe("com.webos.service.tvpower/power/getPowerState").collect { payload ->
                        log { "power: $payload" }
                        payload.string("state")?.let { powerState = it }
                    }
                }
            },
            viewModelScope.launch {
                // shortcut: the payload shape of a TV keyboard event isn't measured yet (2026-10-22); currentWidget.focus is
                // what LG's own apps read. Logged in dev builds until it's confirmed on Juan's TV.
                runCatching {
                    c.subscribe("com.webos.service.ime/registerRemoteKeyboard").collect { payload ->
                        log { "keyboard: $payload" }
                        payload["currentWidget"]?.jsonObject?.get("focus")?.jsonPrimitive?.booleanOrNull?.let { tvKeyboard = it }
                    }
                }
            },
        )
        try {
            c.closed.first { it }
        } finally {
            watchers.forEach { it.cancel() }
            // A newer session may already own the link: only the current one reports it closed.
            if (client === c) {
                disconnect()
                if (link == Link.Connected) link = Link.Connecting
            }
        }
    }

    private suspend fun loadApps(c: TvClient) {
        runCatching { c.request("com.webos.applicationManager/listLaunchPoints") }.getOrNull()?.let { payload ->
            val list = payload["launchPoints"]?.jsonArray.orEmpty().mapNotNull {
                val o = it.jsonObject
                val id = o.string("id") ?: return@mapNotNull null
                TvApp(id, o.string("title") ?: id, o.string("largeIcon") ?: o.string("icon"))
            }
            apps.clear()
            apps += list
            // Icons after the list, so the grid shows at once; each arrives on its own. Already cached ones are skipped.
            for (app in list) {
                val url = app.icon?.takeIf { it.startsWith("http") } ?: continue
                if (app.id in icons) continue
                c.fetch(url)?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { icons[app.id] = it.asImageBitmap() } }
            }
        }
        runCatching { c.request("tv/getExternalInputList") }.getOrNull()?.let { payload ->
            val list = payload["devices"]?.jsonArray.orEmpty().mapNotNull {
                val o = it.jsonObject
                val id = o.string("id") ?: return@mapNotNull null
                TvInput(id, o.string("label") ?: id, o.string("appId"), o["connected"]?.jsonPrimitive?.booleanOrNull == true)
            }
            // Connected first: on Juan's TV HDMI 1 is empty and HDMI 2 is the PC.
            inputs.clear()
            inputs += list.sortedByDescending { it.connected }
        }
    }

    /** What's on now, as the person knows it: the app's or input's name. Null when unknown. */
    fun foregroundName(): String? {
        val id = foreground ?: return null
        return apps.firstOrNull { it.id == id }?.title ?: inputs.firstOrNull { it.appId == id }?.label
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
        tvKeyboard = false
        powerState = null
    }

    // Presses. Without a socket they queue (fresh ones are sent on reconnect) and start a reconnect.

    private fun press(action: Press) {
        val c = client
        if (c != null && link == Link.Connected) return action(c, pointer)
        queue.add(System.currentTimeMillis(), action)
        if (link == Link.Off || link == Link.Connected) connect()
    }

    fun button(name: String) = press { c, p ->
        // The pointer socket can drop alone: reopen it and send the press there.
        if (p?.button(name) != true) viewModelScope.launch {
            runCatching { c.pointer() }.getOrNull()?.let { fresh -> pointer = fresh; fresh.button(name) }
        }
    }

    fun request(uri: String, payload: JsonObject = JsonObject(emptyMap())) = press { c, _ ->
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
        client?.fire("system/turnOff")
    }

    fun launch(app: TvApp) {
        request("system.launcher/launch", buildJsonObject { put("id", app.id) })
        tv?.let { save(it.copy(uses = it.uses + (app.id to (it.uses[app.id] ?: 0) + 1))) }
    }

    /** "Play on TV" from the share sheet: false when the text has no link. */
    fun share(text: String): Boolean {
        pendingShare = shareLaunch(text) ?: return false
        val c = client
        if (c != null && link == Link.Connected) sendShare(c) else if (link == Link.Off || link == Link.Connected) connect()
        return true
    }

    private fun sendShare(c: TvClient) {
        val launch = pendingShare ?: return
        pendingShare = null
        val payload = buildJsonObject {
            put("id", launch.appId)
            launch.contentId?.let { put("contentId", it) }
            launch.target?.let { put("target", it) }
        }
        viewModelScope.launch {
            runCatching { c.request("system.launcher/launch", payload) }
                .onSuccess { log { "share $payload: $it" } }.onFailure { log { "share $payload failed: $it" } }
        }
    }

    fun switchInput(input: TvInput) = request("tv/switchInput", buildJsonObject { put("inputId", input.id) })

    /** Live typing: the TV's field follows the phone's, as the smallest delete-then-insert from [before] to [after]. */
    fun typeChange(before: String, after: String) {
        val common = before.commonPrefixWith(after).length
        val deletes = before.length - common
        if (deletes > 0) request("com.webos.service.ime/deleteCharacters", buildJsonObject { put("count", deletes) })
        if (after.length > common) request("com.webos.service.ime/insertText", buildJsonObject { put("text", after.substring(common)); put("replace", 0) })
    }

    fun enter() = request("com.webos.service.ime/sendEnterKey")
    fun delete() = request("com.webos.service.ime/deleteCharacters", buildJsonObject { put("count", 1) })

    fun move(dx: Int, dy: Int) {
        pointer?.move(dx, dy)
    }

    fun click() = press { _, p -> p?.click() }

    fun scroll(dx: Int, dy: Int) {
        pointer?.scroll(dx, dy)
    }

    override fun onCleared() {
        hid?.stop()
        cm.unregisterNetworkCallback(wifi)
        session?.cancel()
        disconnect()
    }

    private companion object {
        /** Power states that mean the TV looks off to the person (aiowebostv's is_on / is_screen_on). */
        val ASLEEP = setOf("Power Off", "Suspend", "Active Standby", "Screen Off")

        /** How long the socket outlives the screen: covers locking the phone and a quick app switch. */
        const val GRACE_MS = 30_000L
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
