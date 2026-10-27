package dev.unsubscribed.controlled

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay

enum class Sheet { Apps, Inputs, Keyboard, Touchpad }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(model: RemoteModel, switch: () -> Unit) {
    val tv = model.tv ?: return
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var editingKey by remember { mutableStateOf(false) }
    val askBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // Keys stay live while reconnecting: presses queue and go out the moment the socket is back.
    val enabled = model.link != Link.CertificateChanged && model.link != Link.NoWifi

    // The TV's own keyboard came up: open Type, and close it again when the TV's field lets go.
    LaunchedEffect(model.tvKeyboard) {
        if (model.tvKeyboard) sheet = Sheet.Keyboard else if (sheet == Sheet.Keyboard) sheet = null
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Header(model, tv, switch, onRename = { renaming = true }, onBleKey = { editingKey = true })
            if (model.link == Link.CertificateChanged) CertificateNotice(model::forget)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width = maxWidth
                val height = maxHeight
                val landscape = width > height
                if (landscape) Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        DPad(model, enabled, min(height - 96.dp, 248.dp))
                        Spacer(Modifier.height(12.dp))
                        NavPills(model, enabled)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Dock(model, tv, enabled) { sheet = Sheet.Apps }
                        Rockers(model, enabled)
                        BottomRow(enabled) { sheet = it }
                    }
                } else {
                    // Fills the height when there's room; scrolls on small phones instead of squeezing the keys.
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).heightIn(min = height).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Dock(model, tv, enabled) { sheet = Sheet.Apps }
                        DPad(model, enabled, min(width * 0.7f, 248.dp))
                        NavPills(model, enabled)
                        Rockers(model, enabled)
                        BottomRow(enabled) { sheet = it }
                    }
                }
            }
        }
        VolumeHud(model.volume, model.muted, Modifier.align(Alignment.TopCenter).padding(top = 72.dp))
    }

    sheet?.let { open ->
        // The touchpad's drags are its own, not the sheet's.
        ModalBottomSheet({ sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetGesturesEnabled = open != Sheet.Touchpad) {
            when (open) {
                Sheet.Apps -> AppsSheet(model) { sheet = null }
                Sheet.Inputs -> InputsSheet(model) { sheet = null }
                Sheet.Keyboard -> KeyboardSheet(model)
                Sheet.Touchpad -> TouchpadSheet(model) { sheet = null }
            }
        }
    }
    if (renaming) RenameDialog(tv.name, { renaming = false }) { model.rename(it); renaming = false }
    if (editingKey) BleKeyDialog(tv.bleKey.orEmpty(), { editingKey = false }) { key ->
        model.setBleKey(key).also { ok ->
            if (ok) {
                editingKey = false
                if (key.isNotBlank() && Build.VERSION.SDK_INT >= 31) askBluetooth.launch(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        }
    }
}

@Composable
private fun Header(model: RemoteModel, tv: PairedTv, switch: () -> Unit, onRename: () -> Unit, onBleKey: () -> Unit) {
    val t = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    val live = model.link == Link.Connected && !model.asleep
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            ControllerTitle(tv.name, switch)
            StatusLine(model, tv)
        }
        Key(R.drawable.sym_power_settings_new, if (live) t.powerOff else t.powerOn, size = 48.dp, accent = !live,
            enabled = model.link != Link.Waking && model.link != Link.CertificateChanged && model.link != Link.NoWifi) {
            if (live) model.powerOff() else model.powerOn()
        }
        Box {
            IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.sym_more_vert), t.more) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(t.rename) }, { menu = false; onRename() }, leadingIcon = { Icon(painterResource(R.drawable.sym_tv), null) })
                DropdownMenuItem({ Text(t.forgetTv) }, { menu = false; model.forget() }, leadingIcon = { Icon(painterResource(R.drawable.sym_link_off), null) })
                DropdownMenuItem({ Text(t.bleKey) }, { menu = false; onBleKey() })
                DropdownMenuItem({ Text(t.privacy) }, { menu = false; uri.openUri(BuildConfig.PRIVACY_URL) })
            }
        }
    }
}

/**
 * One dot and one short line: what's on when connected, otherwise what's happening and what a tap does. "Connecting…"
 * waits 400 ms, so a quick reconnect never flashes. One polite live region, so TalkBack hears each change once.
 */
@Composable
private fun StatusLine(model: RemoteModel, tv: PairedTv) {
    val t = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(model.link) {
        slow = false
        delay(400)
        slow = true
    }
    // Connected to a TV in standby (Quick Start+ keeps its socket open) reads as off, with Turn on a tap away.
    val sleeping = model.link == Link.Connected && model.asleep
    val text = when (model.link) {
        Link.Connected -> if (sleeping) t.tvOff else model.foregroundName()?.let { t.nowOn(it) }
        Link.Connecting -> if (slow) t.connecting else null
        Link.Waking -> t.turningOn
        Link.Off -> if (model.wakeFailed) t.wakeHint else if (tv.macs.isNotEmpty()) t.tvOff else t.tvOffNoPower
        Link.NoWifi -> t.noWifi
        Link.CertificateChanged -> null
    }
    val tap: (() -> Unit)? = when {
        sleeping -> model::powerOn
        model.link == Link.Off -> if (tv.macs.isNotEmpty()) model::powerOn else model::connect
        else -> null
    }
    val breathing = model.link == Link.Waking || (model.link == Link.Connecting && slow)
    val pulse = if (breathing && !reducedMotion()) {
        rememberInfiniteTransition(label = "status").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot").value
    } else 1f
    Row(
        Modifier.heightIn(min = 32.dp)
            .then(if (tap != null) Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = tap) else Modifier)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = Modifier.size(8.dp).alpha(pulse).clip(CircleShape)
        when (model.link) {
            Link.Connected -> if (sleeping) Box(dot.border(1.5.dp, colors.onSurfaceVariant, CircleShape)) else Box(dot.background(colors.primary))
            Link.Connecting, Link.Waking -> Box(dot.background(colors.primary))
            Link.Off, Link.NoWifi -> Box(dot.border(1.5.dp, colors.onSurfaceVariant, CircleShape))
            Link.CertificateChanged -> Box(dot.border(1.5.dp, colors.error, CircleShape))
        }
        text?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CertificateNotice(forget: () -> Unit) {
    val t = LocalStrings.current
    Surface(Modifier.fillMaxWidth().padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(t.certificateChanged, color = MaterialTheme.colorScheme.onErrorContainer)
            Button(forget, Modifier.align(Alignment.End)) { Text(t.forgetTv) }
        }
    }
}

/** Four of the person's apps (pinned, then most used), then All apps: five columns leave room for 60dp icons with 8dp between. */
@Composable
private fun Dock(model: RemoteModel, tv: PairedTv, enabled: Boolean, all: () -> Unit) {
    val t = LocalStrings.current
    val docked = dock(model.apps, tv.pins, tv.uses, size = 4)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        for (app in docked) AppTile(app, model, Modifier.weight(1f), enabled, compact = true) { model.launch(app) }
        Column(Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable(onClickLabel = t.allApps, onClick = all).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(ICON_DOCK).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.sym_apps), null)
            }
            Text(t.allApps, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// App icon sizes (agy draft, reviewed): on a 375dp phone the dock's 5 columns are ~68dp and the sheet's 4 are ~88dp.
val ICON_DOCK = 60.dp
val ICON_GRID = 72.dp

/** An app as the TV shows it: its own icon (a monogram until the icon arrives), its name below. */
@Composable
fun AppTile(app: TvApp, model: RemoteModel, modifier: Modifier, enabled: Boolean, compact: Boolean, badge: (@Composable () -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val size = if (compact) ICON_DOCK else ICON_GRID
    Column(
        modifier.clip(MaterialTheme.shapes.medium)
            .then(if (onLongClick != null) Modifier.combinedClickableCompat(enabled, app.title, onClick, onLongClick) else Modifier.clickable(enabled, onClickLabel = app.title, onClick = onClick))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size)) {
            AppIcon(app, model, Modifier.fillMaxSize())
            badge?.let { Box(Modifier.align(Alignment.TopEnd)) { it() } }
        }
        Text(app.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun AppIcon(app: TvApp, model: RemoteModel, modifier: Modifier) {
    val icon = model.icons[app.id]
    Box(modifier.aspectRatio(1f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize())
        else Text(app.title.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/** One big round d-pad with OK in the middle; the arrows repeat while held. */
@Composable
private fun DPad(model: RemoteModel, enabled: Boolean, size: Dp) {
    val t = LocalStrings.current
    val arrow = size * 0.26f
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_up, t.up, arrow, enabled, repeat = true) { model.button("UP") } }
        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_down, t.down, arrow, enabled, repeat = true) { model.button("DOWN") } }
        Box(Modifier.align(Alignment.CenterStart).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_left, t.left, arrow, enabled, repeat = true) { model.button("LEFT") } }
        Box(Modifier.align(Alignment.CenterEnd).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_right, t.right, arrow, enabled, repeat = true) { model.button("RIGHT") } }
        KeyFace(t.ok, CircleShape, Modifier.align(Alignment.Center).size(size * 0.39f), enabled, onClick = { model.button("ENTER") }) { fg ->
            Text(t.ok, style = MaterialTheme.typography.titleLarge, color = fg)
        }
    }
}

/** Back, Home, Menu: soft pills under the d-pad. */
@Composable
private fun NavPills(model: RemoteModel, enabled: Boolean) {
    val t = LocalStrings.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((icon, label, key) in listOf(Triple(R.drawable.sym_arrow_back, t.back, "BACK"), Triple(R.drawable.sym_home, t.home, "HOME"), Triple(R.drawable.sym_menu, t.menu, "MENU"))) {
            KeyFace(label, CircleShape, Modifier.height(56.dp).width(96.dp), enabled, onClick = { model.button(key) }) { fg ->
                Icon(painterResource(icon), null, tint = fg)
            }
        }
    }
}

/** Volume (with the live level, which mutes on tap), play and pause, channel. Both rockers repeat while held. */
@Composable
private fun Rockers(model: RemoteModel, enabled: Boolean) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Rocker {
            Key(R.drawable.sym_add, t.volumeUp, enabled = enabled, repeat = true, onClick = model::volumeUp)
            KeyFace(t.volumeReadout(model.volume, model.muted), CircleShape, Modifier.size(56.dp), enabled, accent = model.muted, onClick = model::toggleMute) { fg ->
                if (model.muted || model.volume == null) Icon(painterResource(if (model.muted) R.drawable.sym_volume_off else R.drawable.sym_volume_up), null, tint = fg)
                else Text("${model.volume}", style = MaterialTheme.typography.titleMedium, color = fg)
            }
            Key(R.drawable.sym_remove, t.volumeDown, enabled = enabled, repeat = true, onClick = model::volumeDown)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Key(R.drawable.sym_play_arrow, t.play, enabled = enabled, onClick = model::play)
            Key(R.drawable.sym_pause, t.pause, enabled = enabled, onClick = model::pause)
        }
        Rocker {
            Key(R.drawable.sym_keyboard_arrow_up, t.channelUp, enabled = enabled, repeat = true, onClick = model::channelUp)
            Text(t.channel, Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.labelLarge)
            Key(R.drawable.sym_keyboard_arrow_down, t.channelDown, enabled = enabled, repeat = true, onClick = model::channelDown)
        }
    }
}

@Composable
private fun Rocker(content: @Composable () -> Unit) {
    Column(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        content()
    }
}

@Composable
private fun BottomRow(enabled: Boolean, open: (Sheet) -> Unit) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((sheet, icon, label) in listOf(Triple(Sheet.Keyboard, R.drawable.sym_keyboard, t.keyboard), Triple(Sheet.Touchpad, R.drawable.sym_touch_app, t.touchpad), Triple(Sheet.Inputs, R.drawable.sym_input, t.inputs))) {
            Surface({ open(sheet) }, Modifier.weight(1f).height(64.dp), enabled = enabled, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(painterResource(icon), null)
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A brief level readout whenever the volume changes (the rocker, the phone's keys or the TV's own remote). */
@Composable
private fun VolumeHud(volume: Int?, muted: Boolean, modifier: Modifier) {
    val t = LocalStrings.current
    var shown by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(volume, muted) {
        if (volume == null) return@LaunchedEffect
        if (first) { first = false; return@LaunchedEffect } // the first reading on connect isn't a change
        shown = true
        delay(1_200)
        shown = false
    }
    AnimatedVisibility(shown, modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.inverseSurface, shadowElevation = 6.dp) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(if (muted) R.drawable.sym_volume_off else R.drawable.sym_volume_up), null, tint = MaterialTheme.colorScheme.inverseOnSurface)
                Spacer(Modifier.width(12.dp))
                if (muted) Text(t.muted, color = MaterialTheme.colorScheme.inverseOnSurface)
                else {
                    LinearProgressIndicator({ (volume ?: 0) / 100f }, Modifier.width(120.dp), color = MaterialTheme.colorScheme.inversePrimary)
                    Spacer(Modifier.width(12.dp))
                    Text("${volume ?: ""}", color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Advanced: the Bluetooth wake key, pasted by someone who captured it (docs/research/lg-power-on.md). */
@Composable
private fun BleKeyDialog(current: String, dismiss: () -> Unit, save: (String) -> Boolean) {
    val t = LocalStrings.current
    var key by remember { mutableStateOf(current) }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(t.bleKey) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.bleKeyHelp, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(key, { key = it; bad = false }, label = { Text(t.bleKeyLabel) }, singleLine = true, isError = bad,
                    supportingText = if (bad) ({ Text(t.bleKeyInvalid) }) else null)
            }
        },
        confirmButton = { TextButton({ bad = !save(key) }) { Text(t.save) } },
        dismissButton = { TextButton(dismiss) { Text(t.cancel) } },
    )
}

@Composable
fun RenameDialog(current: String, dismiss: () -> Unit, pc: Boolean = false, save: (String) -> Unit) {
    val t = LocalStrings.current
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (pc) t.renamePc else t.renameTitle) },
        text = { OutlinedTextField(name, { name = it }, label = { Text(if (pc) t.pcName else t.tvName) }, singleLine = true) },
        confirmButton = { TextButton({ save(name) }, enabled = name.isNotBlank()) { Text(t.save) } },
        dismissButton = { TextButton(dismiss) { Text(t.cancel) } },
    )
}
