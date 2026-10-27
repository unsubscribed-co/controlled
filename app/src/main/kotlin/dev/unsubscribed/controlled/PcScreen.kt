package dev.unsubscribed.controlled

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import android.content.res.Configuration
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** The PC: a trackpad, its two buttons, the keys a setup needs, media keys, and live typing. Or, while adding one, the steps. */
@Composable
fun PcScreen(model: RemoteModel, switch: () -> Unit) {
    val t = LocalStrings.current
    val context = LocalContext.current
    val needed = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyArray()
    fun granted() = needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = granted()
        denied = !allowed
        if (allowed) model.start()
    }
    if (!allowed) return PermissionScreen(denied, ask = { ask.launch(needed) }, title = t.addPcTitle, body = t.pcPermission, icon = R.drawable.sym_computer, settings = {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
    })

    val hid = model.hid ?: return
    val pc = model.pc
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ControllerTitle(pc?.name ?: t.addPcTitle, switch)
                PcStatus(hid.link)
            }
        }
        if (pc == null) AddPc(model) else Pad(hid)
    }
}

@Composable
private fun PcStatus(link: PcLink) {
    val t = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    val text = when (link) {
        PcLink.Connected -> t.pcConnected
        PcLink.Waiting -> t.pcWaiting
        PcLink.Starting, PcLink.Off -> t.connecting
        PcLink.NoBluetooth -> t.pcNoBluetooth
    }
    Row(Modifier.heightIn(min = 32.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        val dot = Modifier.size(8.dp).clip(CircleShape)
        Box(if (link == PcLink.Connected) dot.background(colors.primary) else dot.border(1.5.dp, colors.onSurfaceVariant, CircleShape))
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

/** Adding a PC: the phone shows up in the PC's Bluetooth list while visible; the PC pairs and connects, and it's saved. */
@Composable
private fun AddPc(model: RemoteModel) {
    val t = LocalStrings.current
    val visible = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    BackHandler(onBack = model::cancelAdd)
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(painterResource(R.drawable.sym_computer), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(t.addPcSteps, style = MaterialTheme.typography.bodyLarge)
        Button({ visible.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)) },
            Modifier.fillMaxWidth().height(56.dp)) { Text(t.makeVisible) }
        TextButton(model::cancelAdd, Modifier.fillMaxWidth()) { Text(t.backToControllers) }
    }
}

/** Live typing: each change goes out as Backspaces then the new characters, as it's typed. Enter sends Enter and clears. */
@Composable
private fun TypeField(hid: PcHid) {
    val t = LocalStrings.current
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        text,
        { new ->
            val common = text.commonPrefixWith(new).length
            repeat(text.length - common) { hid.key(0, Keymap.BACKSPACE) }
            hid.type(new.substring(common))
            text = new
        },
        // The keyboard's own delete key on an empty field arrives as a key press: it still deletes on the PC.
        Modifier.fillMaxWidth().onPreviewKeyEvent {
            if (text.isNotEmpty() || it.key != Key.Backspace) return@onPreviewKeyEvent false
            if (it.type == KeyEventType.KeyDown) hid.key(0, Keymap.BACKSPACE)
            true
        },
        label = { Text(t.typeOnPc) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { hid.key(0, Keymap.ENTER); text = "" }),
    )
}

/**
 * The PC's tools, in groups of eight under the trackpad (4 by 2, every one with a caption): Windows first, since moving
 * between and closing windows is what setting up a PC needs most. Shortcuts are Windows', as a keyboard would send them.
 */
private enum class Tools { Windows, Tabs, Edit, Keys, Media }

/** One shortcut: a caption under its icon (or a keycap when there's no icon), [name] for TalkBack, what it sends. */
private class Shortcut(val icon: Int?, val caption: Strings.() -> String, val name: Strings.() -> String = caption, val repeat: Boolean = false, val send: PcHid.() -> Unit)

private fun ctrl(key: Int): PcHid.() -> Unit = { key(Keymap.CTRL, key) }
private fun win(key: Int): PcHid.() -> Unit = { key(Keymap.GUI, key) }

/** Alt+Tab, sticky (PcHid.switchWindow): its caption changes while the switcher is up. */
private val SWITCH = Shortcut(R.drawable.sym_swap_horiz, { switchWindow }, send = { switchWindow() })

private val TOOLS: Map<Tools, List<Shortcut>> = mapOf(
    Tools.Windows to listOf(
        SWITCH,
        Shortcut(R.drawable.sym_view_carousel, { allWindows }, send = win(Keymap.TAB)),
        Shortcut(R.drawable.sym_close, { closeWindow }, { closeWindowName }, send = { key(Keymap.ALT, Keymap.F4) }),
        Shortcut(R.drawable.sym_window, { start }, send = win(0)),
        Shortcut(R.drawable.sym_minimize, { minimize }, send = win(Keymap.DOWN)),
        Shortcut(R.drawable.sym_fullscreen, { maximize }, send = win(Keymap.UP)),
        Shortcut(R.drawable.sym_splitscreen_left, { snapLeft }, send = win(Keymap.LEFT)),
        Shortcut(R.drawable.sym_splitscreen_right, { snapRight }, send = win(Keymap.RIGHT)),
    ),
    Tools.Tabs to listOf(
        Shortcut(R.drawable.sym_chevron_left, { previous }, { previousTab }, repeat = true) { key(Keymap.CTRL or Keymap.SHIFT, Keymap.TAB) },
        Shortcut(R.drawable.sym_chevron_right, { next }, { nextTab }, repeat = true, send = ctrl(Keymap.TAB)),
        Shortcut(R.drawable.sym_tab_close, { closeTab }, { closeTabName }, send = ctrl(Keymap.letter('w'))),
        Shortcut(R.drawable.sym_add, { newTab }, send = ctrl(Keymap.letter('t'))),
        Shortcut(R.drawable.sym_history, { reopenTab }) { key(Keymap.CTRL or Keymap.SHIFT, Keymap.letter('t')) },
        Shortcut(R.drawable.sym_link, { addressBar }, send = ctrl(Keymap.letter('l'))),
        Shortcut(R.drawable.sym_arrow_back, { goBack }) { key(Keymap.ALT, Keymap.LEFT) },
        Shortcut(R.drawable.sym_refresh, { reload }) { key(0, Keymap.F5) },
    ),
    Tools.Edit to listOf(
        Shortcut(R.drawable.sym_content_copy, { copy }, send = ctrl(Keymap.letter('c'))),
        Shortcut(R.drawable.sym_content_paste, { paste }, send = ctrl(Keymap.letter('v'))),
        Shortcut(R.drawable.sym_content_cut, { cut }, send = ctrl(Keymap.letter('x'))),
        Shortcut(R.drawable.sym_select_all, { selectAll }, send = ctrl(Keymap.letter('a'))),
        Shortcut(R.drawable.sym_undo, { undo }, repeat = true, send = ctrl(Keymap.letter('z'))),
        Shortcut(R.drawable.sym_redo, { redo }, repeat = true, send = ctrl(Keymap.letter('y'))),
        Shortcut(R.drawable.sym_search, { find }, send = ctrl(Keymap.letter('f'))),
        Shortcut(R.drawable.sym_save, { save }, send = ctrl(Keymap.letter('s'))),
    ),
    Tools.Keys to listOf(
        Shortcut(null, { esc }) { key(0, Keymap.ESC) },
        Shortcut(R.drawable.sym_keyboard_tab, { tab }) { key(0, Keymap.TAB) },
        Shortcut(R.drawable.sym_keyboard_return, { enter }) { key(0, Keymap.ENTER) },
        Shortcut(null, { deleteKey }, repeat = true) { key(0, Keymap.DELETE) },
        Shortcut(R.drawable.sym_keyboard_arrow_left, { left }, repeat = true) { key(0, Keymap.LEFT) },
        Shortcut(R.drawable.sym_keyboard_arrow_up, { up }, repeat = true) { key(0, Keymap.UP) },
        Shortcut(R.drawable.sym_keyboard_arrow_down, { down }, repeat = true) { key(0, Keymap.DOWN) },
        Shortcut(R.drawable.sym_keyboard_arrow_right, { right }, repeat = true) { key(0, Keymap.RIGHT) },
    ),
    Tools.Media to listOf(
        Shortcut(R.drawable.sym_skip_previous, { previous }) { media(Hid.PREVIOUS) },
        Shortcut(R.drawable.sym_play_arrow, { playPause }) { media(Hid.PLAY_PAUSE) },
        Shortcut(R.drawable.sym_skip_next, { next }) { media(Hid.NEXT) },
        Shortcut(R.drawable.sym_volume_off, { mute }) { media(Hid.MUTE) },
        Shortcut(R.drawable.sym_volume_down, { volumeDown }, repeat = true) { media(Hid.VOLUME_DOWN) },
        Shortcut(R.drawable.sym_volume_up, { volumeUp }, repeat = true) { media(Hid.VOLUME_UP) },
    ),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pad(hid: PcHid) {
    val t = LocalStrings.current
    var tools by rememberSaveable { mutableStateOf(Tools.Windows) }
    // The keyboard takes half the screen: each group keeps only its first row (its most used), so the trackpad stays usable.
    val typing = WindowInsets.isImeVisible
    val panel: @Composable ColumnScope.() -> Unit = {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Tools.entries.forEachIndexed { i, group ->
                SegmentedButton(
                    selected = tools == group, onClick = { tools = group }, shape = SegmentedButtonDefaults.itemShape(i, Tools.entries.size),
                    // No check mark: five labels need the width.
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    // Without the check mark the fill is the only cue: inverse surface, so the chosen group stands out (>= 3:1).
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.inverseSurface, activeContentColor = MaterialTheme.colorScheme.inverseOnSurface),
                    label = { FitText(when (group) { Tools.Windows -> t.toolsWindows; Tools.Tabs -> t.toolsTabs; Tools.Edit -> t.toolsEdit; Tools.Keys -> t.toolsKeys; Tools.Media -> t.toolsMedia }, MaterialTheme.typography.labelMedium) },
                )
            }
        }
        TOOLS.getValue(tools).chunked(4).take(if (typing) 1 else 2).forEach { row ->
            Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in row) {
                    val switching = s === SWITCH && hid.switching
                    // While Alt is held the switcher is up on the PC: the caption says a tap goes further.
                    val caption = if (switching) t.nextWindow else s.caption(t)
                    Tool(s.icon, caption, s.name(t).takeUnless { switching } ?: caption, Modifier.weight(1f), accent = switching, repeat = s.repeat) { s.send(hid) }
                }
            }
        }
        TypeField(hid)
    }
    // Landscape is too short to stack them: the trackpad on the left, the tools beside it (scrolling if the font is large).
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        Row(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Trackpad(hid, !typing, Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = panel)
        }
    } else Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Trackpad(hid, !typing, Modifier.weight(1f).fillMaxWidth())
        panel()
    }
}

/**
 * One line that shrinks rather than cut a word: captions and group names are fixed-width, and their length varies by
 * language and font size. The floor is 10dp, not sp, so even the largest font size can still fit "All windows".
 */
@Composable
private fun FitText(text: String, style: TextStyle, color: Color = Color.Unspecified) {
    val min = with(LocalDensity.current) { 10.dp.toSp() }
    Text(text, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = style.fontSize))
}

/** One tool: the remote's key face (press colour, haptic, hold to repeat) as a rounded tile, icon over caption, or a keycap. */
@Composable
private fun Tool(icon: Int?, caption: String, name: String, modifier: Modifier, accent: Boolean = false, repeat: Boolean = false, onClick: () -> Unit) {
    KeyFace(name, MaterialTheme.shapes.large, modifier.fillMaxSize(), accent = accent, repeat = repeat, onClick = onClick) { fg ->
        // The side padding keeps a shrunk caption off the tile's rounded edges.
        Column(Modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (icon != null) Icon(painterResource(icon), null, tint = fg)
            FitText(caption, if (icon == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium, fg)
        }
    }
}

/**
 * A laptop trackpad: the surface moves the pointer, and its bottom edge is the two buttons. Drag moves (quicker moves go
 * farther), a tap clicks, tap then touch and drag selects (a double tap double-clicks), as on a laptop. Two fingers: a
 * tap right-clicks, a drag scrolls (the page follows the fingers). Holding a button while another finger drags selects
 * too. Every touch event goes out at once: no batching.
 */
@Composable
private fun Trackpad(hid: PcHid, hint: Boolean, modifier: Modifier) {
    val t = LocalStrings.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    Column(modifier.clip(MaterialTheme.shapes.extraLarge).background(colors.surfaceContainerHighest)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = t.trackpadHint }.pointerInput(hid) {
                val slop = viewConfiguration.touchSlop
                // A tap's click waits TAP_DRAG_MS for a second touch, which turns it into a drag (or a double-click).
                var tapClick: Runnable? = null
                awaitEachGesture {
                    val first = awaitFirstDown()
                    var fingers = 1
                    var moved = 0f
                    val dragging = tapClick?.let { view.removeCallbacks(it) } == true
                    tapClick = null
                    if (dragging) hid.hold(Hid.LEFT, true)
                    var last = first.uptimeMillis
                    // Finger speed in px/ms, smoothed: acceleration follows the hand, not how touch events fall into frames.
                    var speed = 0f
                    // Sub-pixel and sub-notch remainders carry over, so slow moves still move.
                    var carry = Offset.Zero
                    var scroll = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val down = event.changes.filter { it.pressed }
                        if (down.isEmpty()) break
                        fingers = maxOf(fingers, down.size)
                        val raw = down.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / down.size.toFloat()
                        val now = down.maxOf { it.uptimeMillis }
                        speed = speed * 0.6f + raw.getDistance() / (now - last).coerceAtLeast(1) * 0.4f
                        last = now
                        moved += abs(raw.x) + abs(raw.y)
                        if (down.size >= 2) {
                            scroll += raw
                            val notches = Offset((scroll.x / SCROLL_PX).toInt().toFloat(), (scroll.y / SCROLL_PX).toInt().toFloat())
                            if (notches != Offset.Zero) {
                                hid.scroll(notches.y.toInt(), -notches.x.toInt())
                                scroll -= notches * SCROLL_PX
                            }
                        } else if (fingers == 1) {
                            val delta = raw * (BASE_GAIN * (1f + speed * ACCEL).coerceAtMost(MAX_ACCEL)) + carry
                            val dx = delta.x.roundToInt()
                            val dy = delta.y.roundToInt()
                            carry = delta - Offset(dx.toFloat(), dy.toFloat())
                            if (dx != 0 || dy != 0) hid.move(dx, dy)
                        }
                        down.forEach { it.consume() }
                    }
                    when {
                        dragging -> {
                            hid.hold(Hid.LEFT, false)
                            // A second tap that didn't move: the first tap's click too, so the PC sees a double-click.
                            if (moved < slop) hid.click(Hid.LEFT)
                        }
                        moved < slop -> {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            if (fingers >= 2) hid.click(Hid.RIGHT)
                            else tapClick = Runnable { tapClick = null; hid.click(Hid.LEFT) }.also { view.postDelayed(it, TAP_DRAG_MS) }
                        }
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.sym_touch_app), null, Modifier.size(32.dp), tint = colors.onSurfaceVariant.copy(alpha = 0.5f))
                // The gestures, visible: a first-time user can't guess two-finger scroll or tap-and-drag. TalkBack reads the pad's own description.
                if (hint) Text(t.trackpadHint, Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(Modifier.height(64.dp).fillMaxWidth()) {
            PadButton(t.leftClick, Hid.LEFT, hid, Modifier.weight(1f))
            VerticalDivider(color = colors.outlineVariant)
            PadButton(t.rightClick, Hid.RIGHT, hid, Modifier.weight(1f))
        }
    }
}

/** A trackpad button: down while the finger is down, like a real one, so hold it and drag with another finger to select. */
@Composable
private fun PadButton(label: String, button: Int, hid: PcHid, modifier: Modifier) {
    val view = LocalView.current
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxSize().background(if (pressed) colors.primaryContainer else Color.Transparent)
            .semantics(mergeDescendants = true) { role = Role.Button; onClick(label) { hid.click(button); true } }
            .pointerInput(hid, button) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    pressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    hid.hold(button, true)
                    waitForUpOrCancellation()
                    hid.hold(button, false)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (pressed) colors.onPrimaryContainer else colors.onSurfaceVariant)
    }
}

/** How soon after a tap a second touch drags instead (laptops use about this). */
private const val TAP_DRAG_MS = 250L
/** Finger travel per scroll notch, in phone pixels. */
private const val SCROLL_PX = 40f
/** Phone pixels to PC pixels at a slow, precise speed. */
private const val BASE_GAIN = 1.2f
/** Extra gain per px/ms of finger speed, up to [MAX_ACCEL] times: slow is precise, a flick crosses the screen. */
private const val ACCEL = 0.6f
private const val MAX_ACCEL = 3.5f
