package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executor

/** The PC link: the phone as a Bluetooth keyboard and mouse. Unsupported: Bluetooth is on, but this phone refuses to be one. */
enum class PcLink { Off, Starting, Waiting, Connected, NoBluetooth, Unsupported }

/** Smooth mode (docs/plans/pc-wifi.md): the same input over Wi-Fi to Controlled for PC, when that PC runs it. */
enum class Smooth { Off, Pairing, On }

/** How a try at smooth mode ended. */
enum class SmoothEnd { NotFound, Unreachable, Declined, Ended }

/**
 * When to try smooth mode again after [end]; null: only when the person asks. A PC that said no (or didn't answer the
 * code) isn't asked again by itself: it brought the same question back every 20 s (2026-11-10).
 */
fun smoothRetryMs(end: SmoothEnd): Long? = when (end) {
    SmoothEnd.NotFound, SmoothEnd.Unreachable -> Hid.SMOOTH_RETRY_MS
    SmoothEnd.Declined -> null
    SmoothEnd.Ended -> 0L
}

/**
 * The phone as a Bluetooth keyboard + mouse + media keys (Android's HID device profile), so a PC needs nothing installed.
 * Registered only while a PC controller is on screen (plus the remote's grace); one computer at a time.
 * [onHost] reports the computer that connected, so a new one can be saved after it pairs from its Bluetooth settings.
 */
// Only created on Android 9+ (RemoteModel.hid), and BLUETOOTH_CONNECT is asked before a PC controller opens (PcScreen).
@SuppressLint("NewApi", "MissingPermission")
class PcHid(context: Context, private val onHost: (address: String, name: String?) -> Unit) {
    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Executor { app.mainExecutor.execute(it) }

    var link by mutableStateOf(PcLink.Off)
        private set

    var smooth by mutableStateOf(Smooth.Off)
        private set
    /** While pairing smooth mode: the code the PC's dialog must show too. */
    var smoothCode by mutableStateOf<String?>(null)
        private set
    private var wifi: SmoothLink? = null
    /** Bumped on every start and stop, so a search or connection from an earlier screen can't take over. */
    private var smoothRun = 0
    /** The PC smooth mode is searching for or connected to, so repeated starts don't stack searches. */
    private var smoothFor: String? = null
    /** The PC (address) that didn't allow this phone: smooth mode waits for [askSmoothAgain]. Kept while the app runs. */
    var smoothDeclinedBy by mutableStateOf<String?>(null)
        private set

    /** Bluetooth or smooth mode: either way the PC takes input. */
    val connected get() = link == PcLink.Connected || smooth == Smooth.On

    /** Looks for Controlled for PC on this Wi-Fi under the PC's name; while it answers, input goes that way. */
    private fun startSmooth(address: String) {
        if (smoothFor == address || smoothDeclinedBy == address) return
        smoothFor = address
        val run = ++smoothRun
        val name = runCatching { adapter?.getRemoteDevice(address)?.name }.getOrNull() ?: return log { "smooth: no name for $address" }.also { smoothFor = null }
        Thread {
            val to = findSmoothPc(app, name)
            log { "smooth: looked for '$name', found $to" }
            val link = runCatching { SmoothLink(app) }.onFailure { log { "smooth: $it" } }.getOrNull()
            var asked = false
            val ok = link != null && to != null && run == smoothRun && runCatching {
                link.connect(to) { code -> asked = true; handler.post { if (run == smoothRun) { smoothCode = code; smooth = Smooth.Pairing } } }
            }.onFailure { log { "smooth: $it" } }.getOrDefault(false)
            handler.post { smoothCode = null }
            if (link == null || !ok || run != smoothRun) {
                link?.close()
                // Asked and not allowed (a No, or nobody answered in time) is the person's answer; not found is the network's.
                val end = if (to == null) SmoothEnd.NotFound else if (asked) SmoothEnd.Declined else SmoothEnd.Unreachable
                log { "smooth: ended $end" }
                handler.post { if (run == smoothRun) smoothEnded(address, run, end) }
                return@Thread
            }
            handler.post { if (run == smoothRun) { wifi = link; smooth = Smooth.On } }
            link.awaitClose()
            log { "smooth: session closed" }
            handler.post {
                if (wifi === link) { wifi = null; smooth = Smooth.Off }
                link.close()
                if (run == smoothRun) smoothEnded(address, run, SmoothEnd.Ended)
            }
        }.start()
    }

    /** Back to Bluetooth after [end]; tries again when [smoothRetryMs] says, while this PC is still the one on screen. */
    private fun smoothEnded(address: String, run: Int, end: SmoothEnd) {
        smooth = Smooth.Off
        smoothFor = null
        if (end == SmoothEnd.Declined) smoothDeclinedBy = address
        val wait = smoothRetryMs(end) ?: return
        handler.postDelayed({ if (run == smoothRun && target == address) startSmooth(address) }, wait)
    }

    /** The person asks for smooth mode again after the PC said no: the PC shows its question once more. */
    fun askSmoothAgain() {
        smoothDeclinedBy = null
        target?.let(::startSmooth)
    }

    /** A send that fails means the link is gone (the PC quit, the Wi-Fi dropped): close it, so input goes back to Bluetooth. */
    private fun smoothSend(w: SmoothLink, send: SmoothLink.() -> Unit) = io.post {
        runCatching { w.send() }.onFailure { log { "smooth: send failed, back to Bluetooth: $it" }; w.close() }
    }

    private fun stopSmooth() {
        smoothRun++
        smoothFor = null
        wifi?.let { w -> io.post { w.close() } }
        wifi = null
        smooth = Smooth.Off
        smoothCode = null
    }

    /** The smooth link, while it carries input. */
    private fun smoothLink() = wifi.takeIf { smooth == Smooth.On }

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    /** The PC to reconnect to, or null while adding a new one (then the phone waits for a computer to pair). */
    private var target: String? = null
    /** Buttons held for a drag (Hid.LEFT, Hid.RIGHT), sent with every move. */
    private var held = 0

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            log { "hid registered=$registered plugged=${pluggedDevice?.address}" }
            if (!registered) { host = null; if (hid != null) link = PcLink.Waiting; return }
            link = PcLink.Waiting
            // Adding: just wait. Calling a computer already paired with the phone collides with that computer pairing again
            // (measured: Windows' pairing failed 30 s later with an L2CAP config error, and it was left "Paired" without the keyboard).
            val t = target ?: return
            val d = adapter?.getRemoteDevice(t) ?: return
            // The PC may have reconnected by itself before we registered: no state change follows, so take it as is.
            if (safely { hid?.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED }) onConnectionStateChanged(d, BluetoothProfile.STATE_CONNECTED)
            // A PC paired before: connect to it ourselves, so it's back without touching the PC.
            else safely { hid?.connect(d) == true }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            log { "hid ${device.address} state=$state" }
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    link = PcLink.Connected
                    onHost(device.address, device.name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (host?.address == device.address) {
                    host = null
                    if (hid != null) link = PcLink.Waiting
                }
            }
        }
    }

    private val proxy = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            val h = proxy as BluetoothHidDevice
            hid = h
            // Bluetooth is on (checked before asking): a refusal here is the phone not offering the keyboard role.
            if (!register(h)) link = PcLink.Unsupported
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            link = PcLink.Off
        }
    }

    /** Opens the link to the PC at [address], or with null waits for a new computer to pair and connect. */
    fun start(address: String?) {
        // Without Nearby devices every Bluetooth call throws: PcScreen asks first, then starts again.
        if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        target = address
        if (address != null) startSmooth(address) else stopSmooth()
        handler.removeCallbacks(retry)
        if (address != null) handler.postDelayed(retry, Hid.RETRY_MS)
        val h = hid
        if (h != null) {
            // Android drops the registration when the app leaves the screen (a lock is enough): back, register again,
            // and the callback reconnects. It refuses when still registered: then only the target changed.
            if (register(h)) { host = null; link = PcLink.Starting; return }
            if (address != null && host?.address != address) adapter?.getRemoteDevice(address)?.let { d -> safely { h.connect(d) } }
            return
        }
        if (adapter?.isEnabled != true) { link = PcLink.NoBluetooth; return }
        link = PcLink.Starting
        if (!safely { adapter.getProfileProxy(app, proxy, BluetoothProfile.HID_DEVICE) }) link = PcLink.Unsupported
    }

    /**
     * Until the PC is back, try again every [Hid.RETRY_MS]: a PC that slept, rebooted or went out of range doesn't call a
     * keyboard back (the keyboard reconnects), and Bluetooth turned off and on loses the profile. Only while on screen.
     */
    private val retry: Runnable = Runnable {
        val t = target
        // An attempt still paging the PC (5 to 8 s) dies if another starts on top of it: let it finish.
        val paging = t != null && safely { hid?.getConnectionState(adapter?.getRemoteDevice(t)) == BluetoothProfile.STATE_CONNECTING }
        if (t != null && link != PcLink.Connected && link != PcLink.Starting && !paging) start(t) else if (t != null) handler.postDelayed(retry, Hid.RETRY_MS)
    }

    private fun register(h: BluetoothHidDevice): Boolean {
        val sdp = BluetoothHidDeviceAppSdpSettings("Controlled", "Keyboard and mouse", "Unsubscribed", BluetoothHidDevice.SUBCLASS1_COMBO, Hid.DESCRIPTOR)
        // Asks the PC for an 11.25 ms report interval (what Bluetooth mice use) instead of its power-saving default.
        val qos = BluetoothHidDeviceAppQosSettings(BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11_250, BluetoothHidDeviceAppQosSettings.MAX)
        return safely { h.registerApp(sdp, null, qos, main, callback) }
    }

    fun stop() {
        stopSmooth()
        handler.removeCallbacks(retry)
        val h = hid ?: return
        // Never leave Alt held on the PC.
        handler.removeCallbacks(releaseAlt)
        if (switching) releaseAlt.run()
        handler.removeCallbacks(drain)
        handler.removeCallbacks(resync)
        // What's still queued (Alt's release, a key-up) goes first, then the goodbye: nothing stays held on the PC.
        val d = host
        val left = outbox.takeAll()
        io.post {
            if (d != null) left.forEach { (id, r) -> safely { h.sendReport(d, id, r) } }
            safely {
                d?.let { h.disconnect(it) }
                h.unregisterApp()
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
                true
            }
        }
        hid = null
        host = null
        link = PcLink.Off
    }

    /**
     * Reports wait their turn, [Hid.PACE_MS] apart: a burst (select all, delete) congested the link, a key-up was lost,
     * and the PC repeated Backspace until the link went down. Measured: 60 characters typed, then deleted, arrive whole.
     * The send itself runs on [io]: it's a call into the Bluetooth process, 2 ms typically and up to 11, which on the
     * main thread delayed the touches that move the pointer.
     */
    private val outbox = Outbox()
    private var lastSent = 0L
    private val drain: Runnable = Runnable { drain() }

    private fun send(id: Int, report: ByteArray) {
        smoothLink()?.let { w -> smoothSend(w) { report(id, report) }; return }
        if (host != null && outbox.add(id, report)) drain()
    }

    private fun drain() {
        handler.removeCallbacks(drain)
        while (!outbox.isEmpty) {
            val h = hid
            val d = host
            if (h == null || d == null) { outbox.takeAll(); return }
            val wait = lastSent + Hid.PACE_MS - SystemClock.uptimeMillis()
            if (wait > 0) { handler.postDelayed(drain, wait); return }
            val (id, report) = outbox.take()
            io.post { safely { h.sendReport(d, id, report) } }
            lastSent = SystemClock.uptimeMillis()
        }
        handler.removeCallbacks(resync)
        handler.postDelayed(resync, Hid.RESYNC_MS)
    }

    /**
     * Android drops reports silently while the link is congested and still says they were sent, so after each burst
     * the state goes again: a lost key-up or button-up can't leave anything held. Alt stays down while switching.
     */
    private val resync = Runnable {
        val h = hid
        val d = host
        if (h != null && d != null && outbox.isEmpty) {
            val keys = Hid.keyboard(if (switching) Keymap.ALT else 0, 0)
            val buttons = Hid.mouse(held, 0, 0)
            io.post { safely { h.sendReport(d, Hid.KEYBOARD, keys) && h.sendReport(d, Hid.MOUSE, buttons) } }
        }
    }

    /** A Bluetooth call that may throw (permission revoked, Bluetooth turned off mid-call): logged, reported as false. */
    private fun safely(call: () -> Boolean): Boolean = runCatching(call).onFailure { log { "hid: $it" } }.getOrDefault(false)

    /** One report per move, whatever its size (16-bit deltas), so a quick flick never arrives in pieces. */
    fun move(dx: Int, dy: Int) {
        smoothLink()?.let { w -> smoothSend(w) { move(dx, dy) }; return }
        if (host != null && outbox.move(held, dx, dy)) drain()
    }

    fun hold(button: Int, down: Boolean) {
        held = if (down) held or button else held and button.inv()
        send(Hid.MOUSE, Hid.mouse(held, 0, 0))
    }

    fun click(button: Int) {
        send(Hid.MOUSE, Hid.mouse(held or button, 0, 0))
        send(Hid.MOUSE, Hid.mouse(held, 0, 0))
    }

    fun scroll(vertical: Int, horizontal: Int) = send(Hid.MOUSE, Hid.mouse(held, 0, 0, vertical.coerceIn(-127, 127), horizontal.coerceIn(-127, 127)))

    /** One key press and release; [mods] are Keymap's modifier bits. */
    fun key(mods: Int, key: Int) {
        send(Hid.KEYBOARD, Hid.keyboard(mods, key))
        send(Hid.KEYBOARD, Hid.keyboard(0, 0))
    }

    /** The keyboard layout the PC is set to, so typed text comes out as typed. */
    var layout = Layout.LatAm

    /** Types [text] as the PC's [layout] reads keys; characters it can't type are skipped. */
    fun type(text: String) {
        // Smooth mode types the characters themselves: the PC's layout doesn't matter.
        smoothLink()?.let { w -> smoothSend(w) { text(text) }; return }
        for (c in text) Keymap.strokes(c, layout)?.forEach { key(it.mods, it.key) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Handler(HandlerThread("Controlled HID").apply { start() }.looper)
    private val releaseAlt = Runnable { send(Hid.KEYBOARD, Hid.keyboard(0, 0)); switching = false }
    /** Alt is held for Switch: the window switcher is up on the PC. */
    var switching by mutableStateOf(false)
        private set

    /**
     * Alt+Tab as on a keyboard: Alt stays down, so each tap goes one window further back; [SWITCH_PICK_MS] after the last
     * tap Alt lets go and the PC opens the window it's on.
     */
    fun switchWindow() {
        handler.removeCallbacks(releaseAlt)
        switching = true
        send(Hid.KEYBOARD, Hid.keyboard(Keymap.ALT, Keymap.TAB))
        send(Hid.KEYBOARD, Hid.keyboard(Keymap.ALT, 0))
        handler.postDelayed(releaseAlt, Hid.SWITCH_PICK_MS)
    }

    /**
     * Picks Windows' display mode [index] (0 PC screen only, 1 duplicate, 2 extend, 3 second screen only) in its Win+P
     * menu: the menu opens on the current mode, so Home first, then down to the one wanted.
     */
    /**
     * Opens Controlled for PC's Microsoft Store page on the PC, typed over this keyboard: Win+R, the store link, Enter.
     * The person only clicks Install there.
     */
    fun openStore() {
        key(Keymap.GUI, Keymap.letter('r'))
        handler.postDelayed({ type(Hid.STORE_LINK); key(0, Keymap.ENTER) }, Hid.RUN_OPEN_MS)
    }

    fun project(index: Int) {
        key(Keymap.GUI, Keymap.letter('p'))
        handler.postDelayed({
            key(0, Keymap.HOME)
            repeat(index) { key(0, Keymap.DOWN) }
            key(0, Keymap.ENTER)
        }, Hid.PROJECT_OPEN_MS)
    }

    /** A media key (Hid.VOLUME_UP...), pressed and released. */
    fun media(usage: Int) {
        send(Hid.CONSUMER, Hid.consumer(usage))
        send(Hid.CONSUMER, Hid.consumer(0))
    }
}

/**
 * Reports waiting to go out, oldest first (PcHid sends them [Hid.PACE_MS] apart). A move adds to a move still waiting at
 * the back instead of queueing behind it, so the pointer never trails the finger; anything else keeps its place and is
 * never dropped or merged. Unit-tested on the JVM.
 */
class Outbox {
    private val items = ArrayDeque<Pair<Int, ByteArray>>()
    /** The move at the back still waiting, and its sum so far. */
    private var tail: Pair<Int, ByteArray>? = null
    private var tailX = 0
    private var tailY = 0

    val isEmpty get() = items.isEmpty()

    /** Queues [report]; true when the queue was empty (nothing is on its way, so start sending). */
    fun add(id: Int, report: ByteArray): Boolean {
        items.addLast(id to report)
        return items.size == 1
    }

    /** Queues a pointer move with [buttons] held, or adds it to the move still waiting at the back; true as [add]. */
    fun move(buttons: Int, dx: Int, dy: Int): Boolean {
        val t = tail
        if (t != null && items.lastOrNull() === t) {
            tailX += dx
            tailY += dy
            tail = (Hid.MOUSE to Hid.mouse(buttons, tailX, tailY)).also { items[items.size - 1] = it }
            return false
        }
        tailX = dx
        tailY = dy
        val m = Hid.MOUSE to Hid.mouse(buttons, dx, dy)
        tail = m
        items.addLast(m)
        return items.size == 1
    }

    fun take(): Pair<Int, ByteArray> = items.removeFirst()

    /** Everything still waiting, in order, leaving the queue empty. */
    fun takeAll(): List<Pair<Int, ByteArray>> = items.toList().also { items.clear() }
}

/** HID wire format, unit-tested on the JVM: one descriptor with three reports (keyboard, mouse, media keys). */
object Hid {
    const val KEYBOARD = 1
    const val MOUSE = 2
    const val CONSUMER = 3

    const val LEFT = 1
    const val RIGHT = 2

    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val MUTE = 0xE2
    const val PLAY_PAUSE = 0xCD
    const val NEXT = 0xB5
    /** How long after the last Switch tap Alt is let go, picking the window. */
    const val SWITCH_PICK_MS = 900L
    /** How long the Win+P menu gets to open before the keys that pick a mode. */
    const val PROJECT_OPEN_MS = 500L
    /** How often a lost PC is tried again while its screen is open. */
    const val RETRY_MS = 5_000L
    /** How often smooth mode looks for the PC program again while the PC's screen is open. */
    const val SMOOTH_RETRY_MS = 20_000L
    /** Controlled for PC in the Microsoft Store (Partner Center product 9N08NXM529FH). */
    const val STORE_LINK = "ms-windows-store://pdp/?ProductId=9N08NXM529FH"
    /** How long Windows' Run box gets to open before the link is typed. */
    const val RUN_OPEN_MS = 700L
    /**
     * The gap between reports: just slower than the link's 11.25 ms beat (one report per beat). Faster, reports pile up
     * in Android's stack and the pointer trails the finger (measured 150 to 300 ms behind at 8 ms); slower, the pile
     * stays in our queue, where moves merge.
     */
    const val PACE_MS = 12L
    /** How long after the last report the held state is sent again. */
    const val RESYNC_MS = 100L
    const val PREVIOUS = 0xB6

    val DESCRIPTOR = bytes(
        // Keyboard (report 1): modifiers byte, reserved byte, 6 key slots. The standard boot keyboard layout.
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // Mouse (report 2): 3 buttons + 5 pad bits, x and y as 16-bit relative, then wheel and horizontal pan -127..127.
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, MOUSE, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
        // Media keys (report 3): one 16-bit consumer usage, 0 = released.
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    )

    fun keyboard(mods: Int, key: Int) = bytes(mods, 0, key, 0, 0, 0, 0, 0)

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray {
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        return bytes(buttons, x and 0xFF, x shr 8, y and 0xFF, y shr 8, wheel, pan)
    }

    fun consumer(usage: Int) = bytes(usage and 0xFF, usage shr 8)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
