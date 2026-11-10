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

/** Look for the TV at a new address on the 3rd failed try, then about every 15 s: it may have been off at the last look. */
fun searchNow(attempt: Int): Boolean = attempt == 2 || (attempt > 2 && (attempt - 2) % 3 == 0)

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
    val hid = if (Build.VERSION.SDK_INT >= 28) PcHid(app) { address, name ->
        if (screen == Screen.Pc && pc == null) {
            savePc(PairedPc(address, name ?: "PC", Layout.guess()))
            askLayout = true
        }
    } else null
    /**
     * A PC was just added: ask which keyboard it uses. A Bluetooth keyboard can't read the PC's layout, and the guess from
     * the phone's language is wrong for an English phone and a Latin American PC (":" and "/" came out as "Ñ" and "-").
     */
    var askLayout by mutableStateOf(false)
        private set

    fun layoutAsked() {
        askLayout = false
    }
    /** The link to the TV: changed only through [next] (Connection.kt), so it can't contradict itself. */
    var status by mutableStateOf<Status>(Status.Connecting)
        private set

    private fun on(e: Event) {
        val was = status
        status = next(was, e)
        if (status != was) log { "status: $was -> $status ($e)" }
    }

    val isConnected get() = status is Status.Connected
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
    /** Connected, but the TV is in standby or its screen is off: the remote offers Turn on. */
    val asleep get() = (status as? Status.Connected)?.asleep == true
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

    /** The TV on screen is an Android TV or Google TV: no touchpad, inputs or Bluetooth wake; its apps are a fixed list. */
    val isGtv get() = tv?.brand == Brand.GoogleTv
    /** The code typed for a Google TV isn't the one on its screen. */
    var codeError by mutableStateOf(false)
        private set

    private var client: TvClient? = null
    private var gtv: GtvClient? = null
    private var gtvPairing: GtvPairing? = null
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
            // Every TV and PC is on the home Wi-Fi: without this, Android sends us over mobile data while it rechecks a
            // Wi-Fi that just came back (measured on Juan's POCO, 2026-11-09), and the TV reads as off.
            cm.bindProcessToNetwork(network)
            viewModelScope.launch {
                on(Event.WifiBack)
                if (visible && (status == Status.Connecting || status is Status.Waiting)) connect()
            }
        }

        override fun onLost(network: Network) {
            viewModelScope.launch {
                if (!hasWifi()) {
                    cm.bindProcessToNetwork(null)
                    session?.cancel()
                    disconnect()
                    on(Event.WifiLost)
                }
            }
        }
    }

    init {
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
    }

    /** This app's traffic goes through a VPN: one that takes the home network (Tailscale's subnet routes did) hides the TV. */
    val vpn get() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

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
        if (c != null && isConnected) viewModelScope.launch {
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
            on(Event.Reset)
            hid?.stop()
        }
    }

    fun search() {
        search?.cancel()
        found.clear()
        search = viewModelScope.launch {
            searching = true
            try {
                // LG TVs answer SSDP, Google TVs announce themselves over mDNS: both searches at once, for as long.
                val gtvs = launch { withTimeoutOrNull(5_000) { gtvDiscover(getApplication()).collect(::addFound) } }
                Ssdp.discover(getApplication()).collect(::addFound)
                gtvs.join()
            } finally {
                searching = false
            }
        }
    }

    private fun addFound(tv: FoundTv) {
        if (found.none { it.ip == tv.ip }) found += tv
    }

    /** Pairs with [found] (from the search, or typed by hand with a placeholder name). */
    fun pair(found: FoundTv) {
        search?.cancel()
        findError = null
        session?.cancel()
        disconnect()
        if (found.brand == Brand.GoogleTv) return pairGtv(found)
        session = viewModelScope.launch {
            val c = TvClient(found.ip, http)
            try {
                val pairing = c.connect(clientKey = null, pin = null) { screen = Screen.Pairing(found) }
                val paired = PairedTv(found.ip, found.name, pairing.clientKey, pairing.pin, macs(c))
                save(paired)
                screen = Screen.Remote
                run(c)
                if (visible) connect()
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

    /** A Google TV pairs with a code: this makes the TV show it, then [submitCode] sends it. */
    private fun pairGtv(found: FoundTv) {
        session = viewModelScope.launch {
            try {
                gtvPairing?.cancel()
                gtvPairing = GtvPairing.start(found.ip)
                codeError = false
                screen = Screen.Pairing(found)
            } catch (e: TvException) {
                log { "gtv pairing failed: $e cause=${e.cause}" }
                findError = if (e.kind == TvException.Kind.Rejected) FindError.Rejected else FindError.Unreachable
                screen = Screen.Find
            }
        }
    }

    /** The code the Google TV shows; a typo keeps the pairing open to type it again. */
    fun submitCode(code: String) {
        val pairing = gtvPairing ?: return
        val found = (screen as? Screen.Pairing)?.tv ?: return
        viewModelScope.launch {
            try {
                val pin = pairing.finish(code) ?: run { codeError = true; return@launch }
                gtvPairing = null
                save(PairedTv(found.ip, found.name, clientKey = pin, pin = pin, brand = Brand.GoogleTv))
                screen = Screen.Remote
                connect()
            } catch (e: TvException) {
                gtvPairing = null
                findError = FindError.Rejected
                screen = Screen.Find
                search()
            }
        }
    }

    fun cancelPairing() {
        gtvPairing?.cancel()
        gtvPairing = null
        session?.cancel()
        disconnect()
        screen = Screen.Find
        search()
    }

    /** Forget and re-pair: the only way past a changed certificate. */
    fun forget() {
        tv?.let(::remove)
    }

    /** The TV forgot this phone: drop it and pair again at the same address, straight to the TV's code. */
    fun repair() {
        val paired = tv ?: return
        remove(paired)
        pair(FoundTv(paired.ip, paired.name, null, null, paired.brand))
    }

    /** Opens [controller]: the TV remote or the PC pad; null finds a TV. Only the one on screen holds a connection. */
    fun select(controller: Controller?) {
        session?.cancel()
        disconnect()
        search?.cancel()
        on(Event.Reset)
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
        // Same address only replaces the same brand: an LG and a Google TV on two networks can share 192.168.0.x.
        val i = tvs.indexOfFirst { it.id == paired.id || (it.ip == paired.ip && it.brand == paired.brand) }
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
     * tries again on [retryDelay]'s schedule, and looks for the TV at a new address ([searchNow]: a DHCP or router change).
     */
    fun connect() = keepConnected(wakeUntil = 0L)

    /**
     * The one connection loop, for both brands. Until [wakeUntil] (a Turn on) it tries every second and resends the magic
     * packet every 10 s (measured on Juan's TV: the session was ready 4.6 s after the packet); a wake that never connects
     * by then is reported as failed. The status only changes through [next].
     */
    private fun keepConnected(wakeUntil: Long) {
        val paired = tv ?: return
        if (!hasWifi()) return on(Event.WifiLost)
        session?.cancel()
        disconnect()
        if (wakeUntil > 0) on(Event.WakeStarted)
        if (paired.brand == Brand.LG && paired.macs.isEmpty()) log { "no MACs stored: power on unavailable" }
        session = viewModelScope.launch {
            var attempt = 0
            var lastPacket = 0L
            while (true) {
                val current = tv ?: return@launch
                val waking = System.currentTimeMillis() < wakeUntil
                if (waking && System.currentTimeMillis() - lastPacket >= 10_000) {
                    WakeOnLan.send(getApplication(), current.macs, current.ip)
                    lastPacket = System.currentTimeMillis()
                }
                try {
                    session(current) // returns when a live session ends
                    attempt = 0
                } catch (e: TvException) {
                    log { "connect failed: ${e.kind} $e cause=${e.cause}" }
                    // A Google TV that refuses our certificate was reset or dropped this phone: pair again.
                    val forgotten = current.brand == Brand.GoogleTv && e.kind == TvException.Kind.Rejected
                    if (e.kind == TvException.Kind.CertificateChanged || forgotten) return@launch on(Event.Refused(forgotten))
                    if (!waking) on(Event.WakeTimedOut)
                    if (attempt >= 1) on(Event.Failed)
                    if (!waking && searchNow(attempt) && lookAgain(current, attempt)) continue
                }
                if (!visible) return@launch
                delay(if (waking) 1_000 else retryDelay(attempt++))
            }
        }
    }

    /** One session with the TV, by brand: throws [TvException] if it can't connect, returns when a live one ends. */
    private suspend fun session(current: PairedTv) {
        if (current.brand == Brand.GoogleTv) {
            val g = GtvClient(current.ip, current.pin ?: throw TvException(TvException.Kind.Rejected, "No pairing code stored"))
            try {
                g.open()
            } catch (e: TvException) {
                g.close()
                throw e
            }
            return runGtv(g)
        }
        val c = TvClient(current.ip, http)
        try {
            c.connect(current.clientKey, current.pin)
        } catch (e: TvException) {
            c.close()
            throw e
        }
        run(c)
    }

    /** Connected to a Google TV: its fixed app list, then its state until the socket closes. */
    private suspend fun runGtv(g: GtvClient) {
        gtv = g
        on(Event.Connected)
        apps.clear()
        apps += Gtv.APPS.map { TvApp(it.id, it.title, null) }
        val watchers = listOf(
            // Off reads as LG's standby, so the header and status line offer Turn on the same way.
            viewModelScope.launch {
                g.on.collect {
                    log { "gtv on=$it" }
                    powerState = if (it == false) "Active Standby" else null
                    on(Event.Asleep(it == false))
                }
            },
            viewModelScope.launch {
                g.app.collect { id ->
                    foreground = id
                    val paired = tv
                    if (id != null && paired != null && Gtv.app(id) != null && id !in paired.seen) save(paired.copy(seen = paired.seen + id))
                }
            },
            viewModelScope.launch { g.volume.collect { v -> v?.let { volume = it.first; muted = it.second } } },
        )
        try {
            g.closed.first { it }
        } finally {
            watchers.forEach { it.cancel() }
            if (gtv === g) {
                disconnect()
                on(Event.Ended)
            }
        }
    }

    /** The TV may have a new address: the first look says so on screen, later ones (every ~15 s) quietly. */
    private suspend fun lookAgain(current: PairedTv, attempt: Int): Boolean {
        on(Event.Searching(shown = attempt == 2))
        try {
            return refind(current)
        } finally {
            on(Event.SearchDone)
        }
    }

    /** Tries every TV of its brand the search finds with our key; the one that accepts it is ours. */
    private suspend fun refind(current: PairedTv): Boolean {
        val gtv = current.brand == Brand.GoogleTv
        // The mDNS search never ends by itself: keep what it found when the time is up.
        val candidates = mutableListOf<FoundTv>()
        withTimeoutOrNull(3_500) {
            (if (gtv) gtvDiscover(getApplication()) else Ssdp.discover(getApplication(), 3_000)).take(8).collect { candidates += it }
        }
        for (f in candidates.filter { it.ip != current.ip }) {
            val ok = if (gtv) {
                val g = GtvClient(f.ip, current.pin ?: return false)
                runCatching { g.open() }.isSuccess.also { g.close() }
            } else {
                val c = TvClient(f.ip, http)
                runCatching { c.connect(current.clientKey, current.pin) }.isSuccess.also { c.close() }
            }
            if (ok) {
                log { "TV moved: ${current.ip} -> ${f.ip}" }
                save(current.copy(ip = f.ip))
                return true
            }
        }
        return false
    }

    /** Turn on: the wake shots (Bluetooth, IR, Wake-on-LAN), then [keepConnected] tries every second for [WAKE_MS]. */
    fun powerOn() {
        val paired = tv ?: return
        // shortcut: assumes a Google TV in standby keeps its remote port open, so Power there wakes it; not measured
        // yet. Off the network it only reconnects (no Wake-on-LAN or IR for these yet).
        if (paired.brand == Brand.GoogleTv) return if (isConnected) gtvKey(Gtv.POWER) else connect()
        bleWake(paired)
        irWake(paired)
        if (status == Status.Waking) return // Already waking: another press only fires the IR and Bluetooth shots again.
        val c = client
        if (c != null && isConnected) {
            // The socket is up but the TV is in standby: wake it over SSAP, as aiowebostv does; the packet is a backup.
            viewModelScope.launch {
                WakeOnLan.send(getApplication(), paired.macs, paired.ip)
                val uri = if (powerState == "Screen Off") "com.webos.service.tvpower/power/turnOnScreen" else "system/turnOn"
                runCatching { c.request(uri) }.onSuccess { log { "$uri: $it" } }.onFailure { log { "$uri failed: $it" } }
            }
            return
        }
        keepConnected(wakeUntil = System.currentTimeMillis() + WAKE_MS)
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
        on(Event.Connected)
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
                        payload.string("state")?.let {
                            powerState = it
                            on(Event.Asleep(it in ASLEEP))
                        }
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
                on(Event.Ended)
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
        gtv?.close()
        gtv = null
        tvKeyboard = false
        powerState = null
    }

    // Presses. Without a socket they queue (fresh ones are sent on reconnect) and start a reconnect.

    private fun press(action: Press) {
        val c = client
        if (c != null && isConnected) return action(c, pointer)
        queue.add(System.currentTimeMillis(), action)
        if (status is Status.Waiting || isConnected) connect()
    }

    /** A Google TV's press: sent when connected, else it starts a reconnect (no queue: shortcut, add one if missed). */
    private fun gtvPress(action: (GtvClient) -> Unit) {
        val g = gtv
        if (g != null && isConnected) return action(g)
        if (status is Status.Waiting || isConnected) connect()
    }

    private fun gtvKey(code: Int) = gtvPress { it.key(code) }

    fun button(name: String) {
        if (isGtv) return Gtv.BUTTONS[name]?.let(::gtvKey) ?: Unit
        press { c, p ->
            // The pointer socket can drop alone: reopen it and send the press there.
            if (p?.button(name) != true) viewModelScope.launch {
                runCatching { c.pointer() }.getOrNull()?.let { fresh -> pointer = fresh; fresh.button(name) }
            }
        }
    }

    fun request(uri: String, payload: JsonObject = JsonObject(emptyMap())) = press { c, _ ->
        viewModelScope.launch { runCatching { c.request(uri, payload) } }
    }

    fun volumeUp() = if (isGtv) gtvKey(Gtv.VOLUME_UP) else request("audio/volumeUp")
    fun volumeDown() = if (isGtv) gtvKey(Gtv.VOLUME_DOWN) else request("audio/volumeDown")
    fun toggleMute() = if (isGtv) gtvKey(Gtv.MUTE) else request("audio/setMute", buildJsonObject { put("mute", !muted) })
    fun channelUp() = if (isGtv) gtvKey(Gtv.CHANNEL_UP) else request("tv/channelUp")
    fun channelDown() = if (isGtv) gtvKey(Gtv.CHANNEL_DOWN) else request("tv/channelDown")
    fun play() = if (isGtv) gtvKey(Gtv.PLAY) else request("media.controls/play")
    fun pause() = if (isGtv) gtvKey(Gtv.PAUSE) else request("media.controls/pause")

    // The TV answers turnOff unreliably as it shuts down, so don't wait for it.
    fun powerOff() {
        if (isGtv) gtv?.key(Gtv.POWER) else client?.fire("system/turnOff")
    }

    fun launch(app: TvApp) {
        if (isGtv) Gtv.launchLink(app.id, tv?.seen.orEmpty())?.let { link -> gtvPress { it.launch(link) } }
        else request("system.launcher/launch", buildJsonObject { put("id", app.id) })
        tv?.let { save(it.copy(uses = it.uses + (app.id to (it.uses[app.id] ?: 0) + 1))) }
    }

    /** "Play on TV" from the share sheet: false when the text has no link. */
    fun share(text: String): Boolean {
        // A Google TV takes the link itself and opens it in the app that claims it.
        if (isGtv) return Regex("""https?://\S+""").find(text)?.value?.let { link -> gtvPress { it.launch(link) }; true } ?: false
        pendingShare = shareLaunch(text) ?: return false
        val c = client
        if (c != null && isConnected) sendShare(c) else if (status is Status.Waiting || isConnected) connect()
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
        if (isGtv) return gtvPress { g -> repeat(deletes) { g.key(Gtv.DEL) }; g.text(after.substring(common)) }
        if (deletes > 0) request("com.webos.service.ime/deleteCharacters", buildJsonObject { put("count", deletes) })
        if (after.length > common) request("com.webos.service.ime/insertText", buildJsonObject { put("text", after.substring(common)); put("replace", 0) })
    }

    fun enter() = if (isGtv) gtvKey(Gtv.ENTER) else request("com.webos.service.ime/sendEnterKey")
    fun delete() = if (isGtv) gtvKey(Gtv.DEL) else request("com.webos.service.ime/deleteCharacters", buildJsonObject { put("count", 1) })

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
        /** How long Turn on keeps trying every second before it reports the wake as failed. */
        const val WAKE_MS = 20_000L
    }
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
