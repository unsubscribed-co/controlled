package dev.unsubscribed.controlled

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
            Text(t.permissionTitle, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(t.permissionBody, style = MaterialTheme.typography.bodyLarge)
            if (denied) {
                Spacer(Modifier.height(8.dp))
                Text(t.permissionDenied, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
            Button(ask, Modifier.fillMaxWidth().height(56.dp)) { Text(t.permissionAllow) }
            if (denied) TextButton(settings, Modifier.fillMaxWidth()) { Text(t.openSettings) }
        }
    }
}

@Composable
private fun FindScreen(model: RemoteModel) {
    val t = LocalStrings.current
    var address by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(t.findTitle, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium) }
        model.findError?.let { error ->
            item {
                Text(
                    when (error) { FindError.Unreachable -> t.unreachable; FindError.Rejected -> t.rejected; FindError.NoAnswer -> t.noAnswer },
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(model.found, key = { it.ip }) { tv ->
            Surface(onClick = { model.pair(tv) }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                ListItem(
                    headlineContent = { Text(tv.name, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = tv.model?.let { { Text(it) } },
                    trailingContent = { Text(tv.ip, style = MaterialTheme.typography.labelMedium) },
                    leadingContent = { Icon(painterResource(R.drawable.sym_tv), null, tint = MaterialTheme.colorScheme.primary) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        item {
            if (model.searching) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(t.searching, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    if (model.found.isEmpty()) Text(t.noneFound, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(model::search) { Text(t.searchAgain) }
                }
            }
        }
        item {
            // The fallback when discovery finds nothing: some routers block multicast.
            val connect = { address.trim().takeIf { it.isNotEmpty() }?.let { model.pair(FoundTv(it, "LG TV", null, null)) } }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address, { address = it }, Modifier.fillMaxWidth(),
                    label = { Text(t.typeAddress) }, supportingText = { Text(t.addressHint) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { connect() }),
                )
                FilledTonalButton({ connect() }, Modifier.height(48.dp), enabled = address.isNotBlank()) { Text(t.connect) }
            }
        }
    }
}

/** Waiting for Accept on the TV: a ring that empties over the TV's 60 s, with the seconds inside. */
@Composable
private fun PairingScreen(tv: FoundTv, cancel: () -> Unit) {
    val t = LocalStrings.current
    var left by remember { mutableIntStateOf(60) }
    LaunchedEffect(tv) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).semantics(mergeDescendants = true) { contentDescription = t.secondsLeft(left) }, contentAlignment = Alignment.Center) {
            CircularProgressIndicator({ left / 60f }, Modifier.fillMaxSize(), strokeWidth = 6.dp, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
            Icon(painterResource(R.drawable.sym_tv), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(24.dp))
        Text(t.pairingTitle(tv.name), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        tv.model?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.height(12.dp))
        Text(t.pairingBody, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        TextButton(cancel, Modifier.height(48.dp)) { Text(t.cancel) }
    }
}

// The remote's keys. Shared here so every key presses, glows and repeats the same way.

/**
 * A remote key's touch: fires on touch-down (a remote should feel instant), a haptic tick, and with [repeat] keeps firing
 * while held (400 ms, then every 120 ms), like a physical remote. TalkBack gets a plain button with [label].
 * shortcut: firing on down means a scroll that starts on a key also presses it; only the small-screen fallback scrolls.
 */
@Composable
fun Modifier.remoteKey(source: MutableInteractionSource, label: String, enabled: Boolean, repeat: Boolean, onPress: () -> Unit): Modifier {
    val view = LocalView.current
    val action by rememberUpdatedState(onPress)
    return semantics {
        role = Role.Button
        contentDescription = label
        if (!enabled) disabled()
        onClick(label) { action(); true }
    }.pointerInput(enabled, repeat) {
        if (!enabled) return@pointerInput
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown()
                val press = PressInteraction.Press(down.position)
                source.tryEmit(press)
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                action()
                val repeating = if (repeat) launch {
                    delay(400)
                    while (true) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        action()
                        delay(120)
                    }
                } else null
                val up = waitForUpOrCancellation()
                repeating?.cancel()
                source.tryEmit(if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
            }
        }
    }
}

/** A key's face: ember while pressed (or [accent]), the surface otherwise; no motion under reduced motion. */
@Composable
fun keyColors(source: MutableInteractionSource, accent: Boolean): Pair<Color, Color> {
    val pressed by source.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val spec = if (reducedMotion()) snap<Color>() else tween(120)
    val on = pressed || accent
    val bg by animateColorAsState(if (on) colors.primary else colors.surfaceContainerHighest, spec, label = "key")
    return bg to if (on) colors.onPrimary else colors.onSurface
}

/** A round icon key, at least 56dp: used without looking, in the dark. */
@Composable
fun Key(icon: Int, label: String, size: Dp = 56.dp, enabled: Boolean = true, accent: Boolean = false, repeat: Boolean = false, onClick: () -> Unit) {
    KeyFace(label, CircleShape, Modifier.size(size), enabled, accent, repeat, onClick) { fg ->
        Icon(painterResource(icon), null, tint = fg)
    }
}

@Composable
fun KeyFace(
    label: String,
    shape: Shape,
    modifier: Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
    repeat: Boolean = false,
    onClick: () -> Unit,
    content: @Composable (Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val (bg, fg) = keyColors(source, accent)
    Box(
        modifier.clip(shape).background(bg).remoteKey(source, label, enabled, repeat, onClick),
        contentAlignment = Alignment.Center,
    ) {
        content(if (enabled) fg else fg.copy(alpha = 0.38f))
    }
}
