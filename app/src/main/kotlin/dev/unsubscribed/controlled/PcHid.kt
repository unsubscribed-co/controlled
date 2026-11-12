package dev.unsubscribed.controlled

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
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
import java.util.concurrent.atomic.AtomicLong

/**
 * The PC link: the phone as a Bluetooth keyboard and mouse. Unsupported: Bluetooth is on, but this phone refuses to be
 * one. Busy: it has been one before, so the refusal is another app holding the role (blek did, 2026-11-10).
 */
enum class PcLink { Off, Starting, Waiting, Connected, NoBluetooth, Unsupported, Busy }

/** Smooth mode (docs/plans/pc-wifi.md, pc-bluetooth-rendezvous.md): the same input over Wi-Fi to Controlled for PC, when that PC runs it. */
enum class Smooth { Off, Pairing, On }

/**
 * What's in front of the PC, as far as smooth mode goes (PC 1.0.3+). Admin: a window run as administrator, where Windows
 * ignores simulated clicks and keys but the pointer still moves. Secure: Ctrl+Alt+Del, the lock screen or the UAC
 * prompt, Windows' separate screen, where the pointer stops too (measured 2026-11-10).
 */
enum class Front { Normal, Admin, Secure }

/**
 * Smooth mode is on but input goes over Bluetooth for what Windows blocks: it lasts while that's in front, and after
 * it, until nothing is held, so a press and its release always go the same way.
 */
fun bluetoothHere(chosen: Boolean, front: Front, holding: Boolean) = chosen && (front != Front.Normal || holding)

/**
 * Whether to tell the person, once per blocked screen, that Windows blocks smooth mode there: only when they try what
 * it blocks. Over an administrator window that's clicks, keys, text and scroll; on the secure screen moving too.
 */
fun adminHint(front: Front, hinted: Boolean, move: Boolean) = front != Front.Normal && !hinted && (!move || front == Front.Secure)

/** Ctrl+Alt+Del or Lock PC was just sent: the secure screen is coming, though the PC hasn't said so yet. */
fun effectiveFront(front: Front, expectingSecure: Boolean) = if (expectingSecure && front == Front.Normal) Front.Secure else front

/**
 * Smooth mode is idle and should be tried: the keyboard link is up (so the PC is there), smooth mode is off, and no
 * retry is already scheduled. An attempt in flight or a PC that said no are left alone by startSmooth itself.
 *
 * How each state ends, so none leaves the person stuck:
 * - never tried (just opened or just added): tried at once, and this net tries within 5 s anyway;
 * - an attempt in flight: every wait is bounded (each step 10 s, the person's answer 95 s), and Try again restarts it;
 * - the code on screen: Allow connects; No, 85 s unanswered, or Cancel on the phone count as "declined";
 * - declined: never asked again by itself; the smooth-mode button (shown whenever smooth mode is off) asks again;
 * - not found, no Wi-Fi, another network, paused, busy: retried at 2, 5, 10 s then every 10 s, the reason in the dialog;
 * - on: a dropped session is retried at once.
 */
fun smoothIdle(linkUp: Boolean, smooth: Smooth, retryPending: Boolean) = linkUp && smooth == Smooth.Off && !retryPending

/**
 * Whether a try that ended in [end], after [misses] misses in a row, should show its reason. The network's reasons wait
 * for a second miss: coming back to the app, the first try fails while Android gives the app its network back
 * (2026-11-11: "keeps devices apart" for a moment, then connected 2 s later).
 */
fun showsReason(end: SmoothEnd, misses: Int) = when (end) {
    SmoothEnd.Ended -> false
    SmoothEnd.NoWifi, SmoothEnd.OtherNetwork, SmoothEnd.Isolated, SmoothEnd.Interrupted -> misses > 0
    else -> true
}

/** What the stuck-key watchdog sends: every key up, except Alt while a window switch holds it. */
fun stallRelease(switching: Boolean) = Hid.keyboard(if (switching) Keymap.ALT else 0, 0)

/**
 * When to try smooth mode again after [end] (SmoothLink), the [misses]th miss in a row; null: only when the person
 * asks. A PC that said no (or didn't answer the code) isn't asked again by itself: it brought the same question back
 * every 20 s. The rest: soon at first (the program just opened, Wi-Fi just back), then every [Hid.SMOOTH_RETRY_MS].
 */
fun smoothRetryMs(end: SmoothEnd, misses: Int): Long? = when (end) {
    SmoothEnd.Declined -> null
    SmoothEnd.Ended -> 0L
    else -> listOf(2_000L, 5_000L, 10_000L).getOrElse(misses) { Hid.SMOOTH_RETRY_MS }
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
    /** Whether this phone has ever taken the keyboard role for us: a later refusal is then another app holding it. */
    private val prefs = app.getSharedPreferences("hid", Context.MODE_PRIVATE)
    private val refused get() = if (prefs.getBoolean("worked", false)) PcLink.Busy else PcLink.Unsupported

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
    private var smoothFor by mutableStateOf<String?>(null)
    /** An attempt is going and smooth mode isn't on yet: the dialog says "Trying…" instead of an older reason. */
    val smoothTrying get() = smooth == Smooth.Off && smoothFor != null
    /** The PC (address) that didn't allow this phone: smooth mode waits for [askSmoothAgain]. Kept while the app runs. */
    var smoothDeclinedBy by mutableStateOf<String?>(null)
        private set
    /** The attempt meeting the PC right now, so stopping can end it (it may wait up to 95 s for the person). */
    private var trying: SmoothLink? = null
    /** Misses in a row, for [smoothRetryMs]; back to 0 once connected or stopped. */
    private var smoothMisses = 0
    /** Why smooth mode isn't on for this PC (the offer explains it), and the two addresses when the network is why. */
    var smoothWhy by mutableStateOf<SmoothEnd?>(null)
        private set
    var smoothAddresses by mutableStateOf<Pair<String, String>?>(null)
        private set

    /** Bluetooth or smooth mode: either way the PC takes input. */
    val connected get() = link == PcLink.Connected || smooth == Smooth.On

    /** Meets Controlled for PC over Bluetooth (SmoothLink); while the Wi-Fi session lasts, input goes that way. */
    private fun startSmooth(address: String) {
        if (smoothFor == address || smoothDeclinedBy == address) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        smoothFor = address
        val run = ++smoothRun
        val link = SmoothLink(app)
        trying?.let { t -> io.post { t.close() } } // an attempt for another PC that wasn't stopped
        trying = link
        Thread {
            val end = runCatching {
                link.connect(device) { code -> handler.post { if (run == smoothRun) { smoothCode = code; smooth = Smooth.Pairing } } }
            }.getOrElse { log { "smooth: $it" }; SmoothEnd.NoProgram }
            handler.post { smoothCode = null }
            if (end != null || run != smoothRun) {
                link.close()
                log { "smooth: not on: $end" }
                handler.post { if (run == smoothRun) { smoothAddresses = link.addresses; smoothEnded(address, run, end ?: SmoothEnd.Ended) } }
                return@Thread
            }
            handler.post {
                if (run == smoothRun) {
                    wifi = link; smooth = Smooth.On; smoothMisses = 0; smoothWhy = null
                    // Mid-drag or mid window switch: finish it over Bluetooth (backToSmooth moves to Wi-Fi at the
                    // release). Else input moves to Wi-Fi now, and nothing stays held on the Bluetooth side.
                    if (held != 0 || switching) useBluetooth = true else releaseBluetooth()
                } else link.close() // stopped meanwhile: don't keep a session nobody uses
            }
            link.awaitClose { front -> handler.post { if (wifi === link) frontChanged(front) } }
            log { "smooth: session closed" }
            handler.post {
                if (wifi === link) { wifi = null; smooth = Smooth.Off; frontChanged(Front.Normal); useBluetooth = false }
                link.close()
                if (run == smoothRun) smoothEnded(address, run, SmoothEnd.Ended)
            }
        }.start()
    }

    /** Back to Bluetooth after [end]; tries again when [smoothRetryMs] says, while this PC is still the one on screen. */
    private fun smoothEnded(address: String, run: Int, end: SmoothEnd) {
        smooth = Smooth.Off
        smoothFor = null
        if (showsReason(end, smoothMisses)) smoothWhy = end
        if (end == SmoothEnd.Declined) smoothDeclinedBy = address
        val wait = smoothRetryMs(end, smoothMisses) ?: return
        if (end != SmoothEnd.Ended) smoothMisses++
        smoothRetryPending = true
        handler.postDelayed({
            smoothRetryPending = false
            if (run == smoothRun && target == address) startSmooth(address)
        }, wait)
    }

    /** A retry from [smoothEnded] is on its way: the safety net in [retry] leaves it to keep its backoff. */
    private var smoothRetryPending = false

    /** Wi-Fi is back: look for the PC program now, not at the next retry (up to 20 s later). Only while the PC is open. */
    fun wifiBack() {
        handler.post {
            val t = target
            if (hid != null && t != null && smooth == Smooth.Off) { smoothMisses = 0; startSmooth(t) }
        }
    }

    /** The person asks for smooth mode again (the PC said no, or they fixed the network): try now, not at the next retry. */
    fun askSmoothAgain() {
        smoothDeclinedBy = null
        smoothMisses = 0
        // An attempt still going (that hasn't shown a code) would make this a no-op: end it and start afresh.
        if (smooth == Smooth.Off && smoothFor != null) {
            smoothRun++
            trying?.let { t -> trying = null; io.post { t.close() } }
            smoothFor = null
        }
        target?.let(::startSmooth)
    }

    /**
     * The person closed the code without answering on the PC: the attempt ends, and like a No it isn't asked again by
     * itself (the smooth-mode button asks again).
     */
    fun cancelPairing() {
        val t = target
        smoothRun++
        trying?.let { l -> trying = null; io.post { l.close() } }
        smoothFor = null
        smoothCode = null
        smooth = Smooth.Off
        if (t != null) { smoothDeclinedBy = t; smoothWhy = SmoothEnd.Declined }
    }

    /** A send that fails means the link is gone (the PC quit, the Wi-Fi dropped): close it, so input goes back to Bluetooth. */
    private fun smoothSend(w: SmoothLink, send: SmoothLink.() -> Unit) = io.post {
        runCatching { w.send() }.onFailure { log { "smooth: send failed, back to Bluetooth: $it" }; w.close() }
    }

    private fun stopSmooth() {
        smoothRun++
        trying?.let { t -> trying = null; io.post { t.close() } } // a meeting still waiting on the PC ends now
        smoothFor = null
        smoothRetryPending = false
        smoothMisses = 0
        smoothWhy = null
        smoothAddresses = null
        wifi?.let { w -> io.post { w.close() } }
        wifi = null
        frontChanged(Front.Normal)
        expectingSecure = false
        handler.removeCallbacks(secureLate)
        useBluetooth = false
        smooth = Smooth.Off
        smoothCode = null
    }

    /** What's in front of the PC, from the PC (1.0.3+). Information only: input keeps its way until the person chooses. */
    var front by mutableStateOf(Front.Normal)
        private set
    /** Show, once per blocked screen, that Windows blocks smooth mode there, with Use Bluetooth (PcScreen's snackbar). */
    var adminHintDue by mutableStateOf(false)
        private set
    private var hinted = false
    /** Input goes over Bluetooth for what Windows blocks ([bluetoothHere]): the person chose it, or pressed Ctrl+Alt+Del. */
    private var useBluetooth by mutableStateOf(false)
    /** Ctrl+Alt+Del or Lock PC went out; the PC will report the secure screen ([effectiveFront]). */
    private var expectingSecure = false
    /** The secure screen never came (Windows didn't open it): back to smooth mode. */
    private val secureLate = Runnable { expectingSecure = false; backToSmooth() }

    private fun frontChanged(now: Front) {
        if (now == Front.Secure) {
            expectingSecure = false
            handler.removeCallbacks(secureLate)
        }
        if (now == front) return
        log { "smooth: in front: $now" }
        front = now
        hinted = false
        adminHintDue = false
        backToSmooth()
    }

    /** Back over Wi-Fi once nothing blocks it and nothing is held (else at the release, in smoothLink). */
    private fun backToSmooth() {
        if (useBluetooth && !bluetoothHere(true, effectiveFront(front, expectingSecure), held != 0 || switching)) {
            useBluetooth = false
            releaseBluetooth()
        }
    }

    /**
     * Input just moved from Bluetooth to Wi-Fi: let go of anything the Bluetooth keyboard or mouse still holds (a
     * button whose release now goes over Wi-Fi, where the PC never saw it pressed).
     */
    private fun releaseBluetooth() {
        if (host == null) return
        if (outbox.add(Hid.KEYBOARD, Hid.keyboard(0, 0))) drain()
        if (outbox.add(Hid.MOUSE, Hid.mouse(0, 0, 0))) drain()
    }

    /**
     * Smooth mode is on but input goes over Bluetooth for now, and why: an administrator window, the secure screen, or
     * (Normal) a press still held from one. Null otherwise. PcStatus shows it in place of the smooth-mode label.
     */
    val bluetoothFor get() = if (smooth == Smooth.On && useBluetooth) effectiveFront(front, expectingSecure) else null

    /** Input goes over Wi-Fi: smooth mode is on and nothing has it go over Bluetooth for now. */
    val smoothActive get() = smooth == Smooth.On && !useBluetooth

    /** The smooth link, while it carries input. */
    private fun smoothLink(): SmoothLink? {
        backToSmooth()
        return wifi.takeIf { smooth == Smooth.On && !useBluetooth }
    }

    /**
     * Something is about to go over Wi-Fi: if Windows blocks it in front, it goes over Bluetooth instead, by itself
     * (2026-11-11: with only an offer, the click over an administrator window was lost, and the offer covered the
     * keys). The status label says so ([bluetoothFor]). Without a Bluetooth link there's no way around: only told.
     */
    private fun tried(move: Boolean) {
        if (smoothLink() != null && adminHint(front, hinted, move)) {
            hinted = true
            if (host != null) useBluetoothHere() else adminHintDue = true
        }
    }

    fun adminHintShown() {
        adminHintDue = false
    }

    /** Over Bluetooth from now on, for what's in front: let go of everything over Wi-Fi first, so nothing stays held. */
    fun useBluetoothHere() {
        adminHintDue = false
        val w = smoothLink() ?: return
        handler.removeCallbacks(releaseAlt)
        switching = false
        held = 0
        smoothSend(w) { report(Hid.KEYBOARD, Hid.keyboard(0, 0)) }
        smoothSend(w) { report(Hid.MOUSE, Hid.mouse(0, 0, 0)) }
        useBluetooth = true
        log { "smooth: Bluetooth for what's in front" }
    }

    /**
     * Ctrl+Alt+Del and Lock PC, over Bluetooth even in smooth mode: Windows never takes them from a program that
     * simulates keys, and from a Bluetooth keyboard both work. They open the secure screen, where the pointer only
     * moves over Bluetooth too: input stays on it until the PC reports the screen gone (or it never came in 3 s).
     * Without Bluetooth, the usual way.
     */
    fun realKey(mods: Int, key: Int) {
        if (host == null) return key(mods, key)
        if (smoothLink() != null) {
            useBluetoothHere()
            expectingSecure = true
            handler.removeCallbacks(secureLate)
            handler.postDelayed(secureLate, Hid.SECURE_WAIT_MS)
        }
        for (r in Hid.stroke(mods, key)) if (outbox.add(Hid.KEYBOARD, r)) drain()
    }

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
            prefs.edit().putBoolean("worked", true).apply()
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
                    // Whatever the PC thinks is held from before (a link that dropped mid-press), let go of it first.
                    hid?.let { h -> safely { h.sendReport(device, Hid.KEYBOARD, Hid.keyboard(0, 0)) && h.sendReport(device, Hid.MOUSE, Hid.mouse(0, 0, 0)) } }
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
            if (!register(h)) link = refused
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
        // Another PC (or none): end whatever smooth mode was doing for the last one, so it can't connect to the old PC
        // while this one is on screen, nor show the old PC's reason here.
        if (address != target) stopSmooth()
        target = address
        if (address != null) startSmooth(address)
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
        if (t != null && link != PcLink.Connected && link != PcLink.Starting && !paging) start(t)
        else if (t != null) {
            // Smooth mode's safety net: no path may leave it off with nothing trying and nothing scheduled
            // (2026-11-11: after Add a PC it never started until the app was reopened, so no code ever showed).
            if (smoothIdle(link == PcLink.Connected, smooth, smoothRetryPending)) startSmooth(t)
            handler.postDelayed(retry, Hid.RETRY_MS)
        }
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
        tried(move = false)
        smoothLink()?.let { w -> smoothSend(w) { report(id, report) }; return }
        if (host != null && outbox.add(id, report)) drain()
    }

    private fun drain() {
        handler.removeCallbacks(drain)
        while (!outbox.isEmpty) {
            val h = hid
            val d = host
            if (h == null || d == null) { outbox.takeAll(); return }
            val pace = if (outbox.next == Hid.KEYBOARD) Hid.KEY_PACE_MS else Hid.PACE_MS
            val wait = lastSent + pace - SystemClock.uptimeMillis()
            if (wait > 0) { handler.postDelayed(drain, wait); return }
            val (id, report) = outbox.take()
            posted.incrementAndGet()
            io.post {
                safely { h.sendReport(d, id, report) }
                taken.incrementAndGet()
                moved = SystemClock.uptimeMillis()
            }
            lastSent = SystemClock.uptimeMillis()
            moved = lastSent
        }
        handler.removeCallbacks(resync)
        handler.postDelayed(resync, Hid.RESYNC_MS)
        handler.removeCallbacks(resyncAgain)
        handler.postDelayed(resyncAgain, Hid.RESYNC_AGAIN_MS)
    }

    /** The resync once more, a second later: a key-up lost twice (both copies) is let go of then. */
    private val resyncAgain = Runnable { resync.run() }

    /**
     * Reports handed to [io] and reports Android took, and when the queue last moved, read by [watchdog] from its own
     * thread, so it sees a stall wherever it happens. 2026-11-11: after a key press the phone sent nothing for 10 s
     * (cause not found: no disconnect, the screen kept responding) and the PC repeated "8" until the app was closed.
     */
    private val posted = AtomicLong()
    private val taken = AtomicLong()
    @Volatile private var moved = 0L

    /**
     * If reports are waiting and nothing has gone out for [Hid.STALL_MS] (less than Windows' key-repeat delay), sends
     * "nothing held" itself, straight to Android from this thread, past whatever holds up the queue, so a key the PC
     * saw go down can't start repeating. What's queued still goes out, in order, once the queue moves again. It can't
     * bring a lost character back; it stops the damage. Measured 2026-11-11: dropping the link instead doesn't help,
     * Windows kept repeating the key after the keyboard left.
     */
    private val watchdog = Thread {
        while (true) {
            Thread.sleep(Hid.STALL_CHECK_MS)
            val h = hid
            val d = host
            val busy = posted.get() > taken.get() || !outbox.isEmpty
            if (h == null || d == null || !busy || SystemClock.uptimeMillis() - moved < Hid.STALL_MS) continue
            log { "hid: nothing went out for ${SystemClock.uptimeMillis() - moved} ms with reports waiting: letting go of everything" }
            moved = SystemClock.uptimeMillis()
            // Keys only: a held key repeats on Windows, a held button doesn't, so a slow moment mid-drag never drops it.
            // Alt held for window switching stays held; the switch ends on its own schedule.
            val keys = stallRelease(switching)
            repeat(2) { safely { h.sendReport(d, Hid.KEYBOARD, keys) } }
        }
    }.apply { isDaemon = true; name = "Controlled HID watchdog"; start() }

    /**
     * Android drops reports silently while the link is congested and still says they were sent, so after each burst
     * the state goes again: a lost key-up or button-up can't leave anything held. Alt stays down while switching.
     */
    private val resync = Runnable {
        val h = hid
        val d = host
        if (h != null && d != null && outbox.isEmpty) {
            val viaBluetooth = !smoothActive
            val keys = Hid.keyboard(if (switching && viaBluetooth) Keymap.ALT else 0, 0)
            val buttons = Hid.mouse(if (viaBluetooth) held else 0, 0, 0)
            io.post { safely { h.sendReport(d, Hid.KEYBOARD, keys) && h.sendReport(d, Hid.MOUSE, buttons) } }
        }
    }

    /** A Bluetooth call that may throw (permission revoked, Bluetooth turned off mid-call): logged, reported as false. */
    private fun safely(call: () -> Boolean): Boolean = runCatching(call).onFailure { log { "hid: $it" } }.getOrDefault(false)

    /** One report per move, whatever its size (16-bit deltas), so a quick flick never arrives in pieces. */
    fun move(dx: Int, dy: Int) {
        tried(move = true)
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
    fun key(mods: Int, key: Int) = Hid.stroke(mods, key).forEach { send(Hid.KEYBOARD, it) }

    /**
     * Computers this phone is already paired with over Bluetooth, as (address, name), sorted by name. Add a PC offers
     * them: a computer that knows the phone doesn't connect to it by itself (Windows waits for the keyboard to call), so
     * without this, adding it again meant pairing again (2026-11-11).
     */
    fun pairedComputers(): List<Pair<String, String>> = runCatching {
        adapter?.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
            .map { it.address to (it.name ?: it.address) }
            .sortedBy { it.second.lowercase() }
    }.getOrDefault(emptyList())

    /** Pasted text goes in by the PC's clipboard and Ctrl+V (PC 1.0.6+ in smooth mode); [pasteWarned] says so once. */
    val pastes get() = smoothActive && wifi?.canPaste == true

    /** The person saw, once, that pasting replaces what's copied on the PC. */
    var pasteWarned: Boolean
        get() = prefs.getBoolean("pasteWarned", false)
        set(v) = prefs.edit().putBoolean("pasteWarned", v).apply()

    /** Text pasted on the phone: whole, through the PC's clipboard when it takes that; else typed like any text. */
    fun paste(text: String) {
        tried(move = false)
        smoothLink()?.takeIf { it.canPaste }?.let { w -> smoothSend(w) { paste(text) }; return }
        type(text)
    }

    /**
     * [n] Backspaces: one message in smooth mode (PC 1.0.6+); over Bluetooth a press and release each, then one more
     * release, instead of the full stroke per key, so deleting a long text doesn't keep the PC deleting long after.
     */
    fun erase(n: Int) {
        if (n <= 0) return
        tried(move = false)
        smoothLink()?.let { w ->
            if (w.canPaste) smoothSend(w) { erase(n) } else repeat(n) { smoothSend(w) { report(Hid.KEYBOARD, Hid.keyboard(0, Keymap.BACKSPACE)); report(Hid.KEYBOARD, Hid.keyboard(0, 0)) } }
            return
        }
        if (host == null) return
        repeat(n) {
            if (outbox.add(Hid.KEYBOARD, Hid.keyboard(0, Keymap.BACKSPACE))) drain()
            if (outbox.add(Hid.KEYBOARD, Hid.keyboard(0, 0))) drain()
        }
        if (outbox.add(Hid.KEYBOARD, Hid.keyboard(0, 0))) drain()
    }

    /** The keyboard layout the PC is set to, so typed text comes out as typed. */
    var layout = Layout.LatAm

    /** Types [text] as the PC's [layout] reads keys; characters it can't type are skipped. */
    fun type(text: String) {
        // Smooth mode types the characters themselves: the PC's layout doesn't matter.
        tried(move = false)
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
    /**
     * Dev test (--es stall_test 1, Notepad in front): "8" goes down, then the send path is held up for 3 s, then "8" comes
     * up. Without [watchdog] the PC repeats "8" for about 2.5 s; with it "nothing held" goes out before the repeat starts.
     */
    fun stallTest() {
        send(Hid.KEYBOARD, Hid.keyboard(0, 0x25))
        io.post { Thread.sleep(3_000) }
        send(Hid.KEYBOARD, Hid.keyboard(0, 0))
    }

    fun openStore() {
        send(Hid.KEYBOARD, Hid.keyboard(0, 0)) // nothing left held from before, so Win+R isn't another shortcut
        key(Keymap.GUI, Keymap.letter('r'))
        handler.postDelayed({ type(Hid.STORE_PAGE); key(0, Keymap.ENTER) }, Hid.RUN_OPEN_MS)
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
    /** The report id that goes next, or null. */
    val next get() = items.firstOrNull()?.first

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
    const val SMOOTH_RETRY_MS = 10_000L
    /** How long Ctrl+Alt+Del or Lock PC keeps input on Bluetooth before the PC reports the secure screen. */
    const val SECURE_WAIT_MS = 3_000L
    /**
     * The page that opens Controlled for PC in the Microsoft Store (product 9N08NXM529FH). Letters and dots only, so it
     * types without Shift on US, Spain and Latin America layouts (2026-11-11: typing the ms-windows-store link lost a
     * Shift about once a line; "/" came out "7"); Windows' Run box opens names that start with "www." in the browser.
     * Also the address shown for typing it by hand.
     */
    const val STORE_PAGE = "www.pc.unsubscribed.dev"
    /** How long Windows' Run box gets to open before the address is typed (a slow PC may need more than 0.7 s). */
    const val RUN_OPEN_MS = 1_000L
    /**
     * The gap between reports: just slower than the link's 11.25 ms beat (one report per beat). Faster, reports pile up
     * in Android's stack and the pointer trails the finger (measured 150 to 300 ms behind at 8 ms); slower, the pile
     * stays in our queue, where moves merge.
     */
    const val PACE_MS = 12L
    /**
     * Keyboard reports go further apart: 2026-11-11, typing a link 25 times to a laptop at 12 ms, Android said every
     * report was sent yet about one character in a hundred came out wrong (a lost Shift turned "/" into "7").
     */
    const val KEY_PACE_MS = 30L
    /** How long after the last report the held state is sent again. */
    const val RESYNC_MS = 100L
    const val RESYNC_AGAIN_MS = 1_000L
    /** Text that arrives at once this long or longer is a paste ([isPaste]): it goes by the PC's clipboard. */
    const val PASTE_MIN = 16
    /** Nothing sent for this long with reports waiting: [PcHid] lets go of everything (Windows' key repeat starts after 500 ms by default, 250 ms at its shortest). */
    const val STALL_MS = 300L
    const val STALL_CHECK_MS = 50L
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

    /**
     * One key press as a real keyboard makes it: the modifiers go down first, then the key, the key comes up, then the
     * modifiers, each its own report; "all up" goes twice, so a key stays held (and repeats) only if both are lost. 2026-11-11: with Shift and the key in one report, a laptop took about one in five
     * shifted characters without Shift ("/" came out "7", "?" as "'", "P" as "p").
     */
    fun stroke(mods: Int, key: Int): List<ByteArray> = when {
        mods == 0 -> listOf(keyboard(0, key), keyboard(0, 0), keyboard(0, 0))
        key == 0 -> listOf(keyboard(mods, 0), keyboard(0, 0), keyboard(0, 0))
        else -> listOf(keyboard(mods, 0), keyboard(mods, key), keyboard(mods, 0), keyboard(0, 0), keyboard(0, 0))
    }

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray {
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        return bytes(buttons, x and 0xFF, x shr 8, y and 0xFF, y shr 8, wheel, pan)
    }

    fun consumer(usage: Int) = bytes(usage and 0xFF, usage shr 8)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
