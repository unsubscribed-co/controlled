package dev.unsubscribed.controlled

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun App(model: RemoteModel, updateUrl: String?) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            updateUrl?.let { UpdateBanner(it) }
            when (val screen = model.screen) {
                Screen.Find -> FindScreen(model)
                is Screen.Pairing -> PairingScreen(screen.tv, model::cancelPairing)
                Screen.Remote -> RemoteScreen(model)
            }
        }
    }
}

@Composable
private fun UpdateBanner(url: String) {
    val t = LocalStrings.current
    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t.newVersion, Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer)
        Button({ uri.openUri(url) }) { Text(t.update) }
    }
}

@Composable
fun PermissionScreen(denied: Boolean, ask: () -> Unit, settings: () -> Unit) {
    val t = LocalStrings.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.sym_wifi_find), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text(t.permissionTitle, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(t.permissionBody, style = MaterialTheme.typography.bodyLarge)
            if (denied) {
                Spacer(Modifier.height(8.dp))
                Text(t.permissionDenied, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Button(ask, Modifier.fillMaxWidth()) { Text(t.permissionAllow) }
            if (denied) TextButton(settings, Modifier.fillMaxWidth()) { Text(t.openSettings) }
        }
    }
}

@Composable
private fun FindScreen(model: RemoteModel) {
    val t = LocalStrings.current
    var address by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t.findTitle, style = MaterialTheme.typography.headlineMedium) }
        model.findError?.let { error ->
            item {
                Text(
                    when (error) { FindError.Unreachable -> t.unreachable; FindError.Rejected -> t.rejected; FindError.NoAnswer -> t.noAnswer },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(model.found, key = { it.ip }) { tv ->
            Surface(onClick = { model.pair(tv) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ListItem(
                    headlineContent = { Text(tv.name) },
                    supportingContent = { Text(listOfNotNull(tv.model, tv.ip).joinToString(" · ")) },
                    leadingContent = { Icon(painterResource(R.drawable.sym_tv), null) },
                )
            }
        }
        item {
            if (model.searching) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(t.searching, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (model.found.isEmpty()) Text(t.noneFound, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(model::search) { Text(t.searchAgain) }
            }
        }
        item {
            // The fallback when discovery finds nothing: some routers block multicast.
            val connect = { address.trim().takeIf { it.isNotEmpty() }?.let { model.pair(FoundTv(it, "LG TV", null, null)) } }
            OutlinedTextField(
                address, { address = it }, Modifier.fillMaxWidth(),
                label = { Text(t.typeAddress) }, supportingText = { Text(t.addressHint) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { connect() }),
            )
            FilledTonalButton({ connect() }, enabled = address.isNotBlank()) { Text(t.connect) }
        }
    }
}

@Composable
private fun PairingScreen(tv: FoundTv, cancel: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(R.drawable.sym_tv), null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(t.pairingTitle(tv.name), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(t.pairingBody, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        TextButton(cancel) { Text(t.cancel) }
    }
}

private enum class Sheet { Keyboard, Touchpad }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen(model: RemoteModel) {
    val t = LocalStrings.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    val tv = model.tv ?: return
    val live = model.link == Link.Connected

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tv.name, style = MaterialTheme.typography.titleLarge)
                val status = when (model.link) {
                    Link.Connected -> model.volume?.let { t.volume(it) }
                    Link.Connecting -> t.connecting
                    Link.Waking -> t.turningOn
                    Link.Off, Link.CertificateChanged -> null
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Key(R.drawable.sym_power_settings_new, if (live) t.powerOff else t.powerOn, size = 48.dp, accent = !live,
                enabled = model.link != Link.Waking && model.link != Link.CertificateChanged) {
                if (live) model.powerOff() else model.powerOn()
            }
            Box {
                IconButton({ menu = true }) { Icon(painterResource(R.drawable.sym_more_vert), t.forgetTv) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(t.forgetTv) }, { menu = false; model.forget() }, leadingIcon = { Icon(painterResource(R.drawable.sym_link_off), null) })
                }
            }
        }

        when (model.link) {
            Link.Off -> Notice(t.off) {
                TextButton(model::connect) { Text(t.reconnect) }
                if (tv.macs.isNotEmpty()) Button(model::powerOn) { Text(t.powerOn) }
            }
            Link.CertificateChanged -> Notice(t.certificateChanged) { Button(model::forget) { Text(t.forgetTv) } }
            else -> {}
        }

        LazyRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(model.shortcuts, key = { it.isInput.toString() + it.id }) { s ->
                val view = LocalView.current
                SuggestionChip({ view.press(); model.open(s) }, { Text(s.label) }, enabled = live,
                    icon = if (s.isInput) ({ Icon(painterResource(R.drawable.sym_input), null, Modifier.size(18.dp)) }) else null)
            }
        }

        Spacer(Modifier.weight(1f))
        DPad(model, live)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill(R.drawable.sym_arrow_back, t.back, live) { model.button("BACK") }
            Pill(R.drawable.sym_home, t.home, live) { model.button("HOME") }
            Pill(R.drawable.sym_menu, t.menu, live) { model.button("MENU") }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Rocker(R.drawable.sym_add, t.volumeUp, R.drawable.sym_remove, t.volumeDown, live, model::volumeUp, model::volumeDown) {
                Key(if (model.muted) R.drawable.sym_volume_off else R.drawable.sym_volume_up, t.mute, enabled = live, accent = model.muted, onClick = model::toggleMute)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Key(R.drawable.sym_play_arrow, t.play, enabled = live, onClick = model::play)
                Key(R.drawable.sym_pause, t.pause, enabled = live, onClick = model::pause)
            }
            Rocker(R.drawable.sym_keyboard_arrow_up, t.channelUp, R.drawable.sym_keyboard_arrow_down, t.channelDown, live, model::channelUp, model::channelDown) {
                Text(t.channel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 16.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton({ sheet = Sheet.Keyboard }, Modifier.weight(1f).height(56.dp), enabled = live) {
                Icon(painterResource(R.drawable.sym_keyboard), null); Spacer(Modifier.width(8.dp)); Text(t.keyboard)
            }
            FilledTonalButton({ sheet = Sheet.Touchpad }, Modifier.weight(1f).height(56.dp), enabled = live) {
                Icon(painterResource(R.drawable.sym_touch_app), null); Spacer(Modifier.width(8.dp)); Text(t.touchpad)
            }
        }
    }

    sheet?.let { open ->
        // The touchpad's drags are its own, not the sheet's.
        ModalBottomSheet({ sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), sheetGesturesEnabled = open != Sheet.Touchpad) {
            when (open) {
                Sheet.Keyboard -> KeyboardSheet(model)
                Sheet.Touchpad -> TouchpadSheet(model) { sheet = null }
            }
        }
    }
}

@Composable
private fun Notice(text: String, actions: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp)) {
            Text(text)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

/** A haptic tick on every press; the system's touch feedback setting decides whether it's felt. */
private fun android.view.View.press() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

/** A round key that glows ember while pressed. At least 56dp: used without looking, in the dark. */
@Composable
private fun Key(icon: Int, label: String, size: Dp = 56.dp, enabled: Boolean = true, accent: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val spec = if (reducedMotion()) snap<androidx.compose.ui.graphics.Color>() else tween(120)
    val bg by animateColorAsState(if (pressed || accent) colors.primary else colors.surfaceContainerHighest, spec, label = "key")
    val fg = if (pressed || accent) colors.onPrimary else colors.onSurface
    Box(
        Modifier.size(size).clip(CircleShape).background(bg)
            .clickable(source, null, enabled, label, Role.Button) { view.press(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), label, tint = if (enabled) fg else fg.copy(alpha = 0.38f))
    }
}

/** Back, Home, Menu: soft pills under the d-pad. */
@Composable
private fun Pill(icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    FilledTonalButton({ view.press(); onClick() }, Modifier.height(56.dp).width(96.dp), enabled = enabled) {
        Icon(painterResource(icon), label)
    }
}

/** Volume or channel: up on top, down below, [middle] between, on one tall pill. */
@Composable
private fun Rocker(upIcon: Int, upLabel: String, downIcon: Int, downLabel: String, enabled: Boolean, up: () -> Unit, down: () -> Unit, middle: @Composable () -> Unit) {
    Column(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Key(upIcon, upLabel, enabled = enabled, onClick = up)
        middle()
        Key(downIcon, downLabel, enabled = enabled, onClick = down)
    }
}

/** One big round d-pad with OK in the middle. */
@Composable
private fun DPad(model: RemoteModel, enabled: Boolean) {
    val t = LocalStrings.current
    Box(Modifier.size(248.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.align(Alignment.TopCenter).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_up, t.up, 64.dp, enabled) { model.button("UP") } }
        Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_down, t.down, 64.dp, enabled) { model.button("DOWN") } }
        Box(Modifier.align(Alignment.CenterStart).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_left, t.left, 64.dp, enabled) { model.button("LEFT") } }
        Box(Modifier.align(Alignment.CenterEnd).padding(8.dp)) { Key(R.drawable.sym_keyboard_arrow_right, t.right, 64.dp, enabled) { model.button("RIGHT") } }
        Box(Modifier.align(Alignment.Center)) { OkKey(t.ok, enabled) { model.button("ENTER") } }
    }
}

@Composable
private fun OkKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val spec = if (reducedMotion()) snap<androidx.compose.ui.graphics.Color>() else tween(120)
    val bg by animateColorAsState(if (pressed) colors.primary else colors.surfaceContainerHighest, spec, label = "ok")
    Box(
        Modifier.size(96.dp).clip(CircleShape).background(bg).clickable(source, null, enabled, label, Role.Button) { view.press(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = if (pressed) colors.onPrimary else colors.onSurface)
    }
}

@Composable
private fun KeyboardSheet(model: RemoteModel) {
    val t = LocalStrings.current
    var text by remember { mutableStateOf("") }
    val send = { if (text.isNotEmpty()) { model.type(text); text = "" } }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            text, { text = it }, Modifier.fillMaxWidth(), label = { Text(t.typeHint) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ send() }, Modifier.weight(1f).height(56.dp), enabled = text.isNotEmpty()) { Text(t.send) }
            Key(R.drawable.sym_backspace, t.deleteChar, onClick = model::delete)
            Key(R.drawable.sym_keyboard_return, t.enter, onClick = model::enter)
        }
    }
}

/** Drag moves the pointer, a tap clicks, two fingers scroll. */
@Composable
private fun TouchpadSheet(model: RemoteModel, close: () -> Unit) {
    val t = LocalStrings.current
    val view = LocalView.current
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(320.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        var moved = 0f
                        // Sub-pixel remainders carry over, so slow drags still move the TV's pointer.
                        var carry = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            val delta = down.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / down.size.toFloat() + carry
                            val dx = delta.x.roundToInt()
                            val dy = delta.y.roundToInt()
                            carry = delta - Offset(dx.toFloat(), dy.toFloat())
                            moved += abs(dx) + abs(dy)
                            if (dx != 0 || dy != 0) if (down.size >= 2) model.scroll(dx, dy) else model.move(dx, dy)
                            down.forEach { it.consume() }
                        }
                        if (moved < viewConfiguration.touchSlop) { view.press(); model.click() }
                        first.consume()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(t.touchpadHint, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
        }
        TextButton(close) { Text(t.close) }
    }
}
