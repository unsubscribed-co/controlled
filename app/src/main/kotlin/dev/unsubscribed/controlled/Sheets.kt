package dev.unsubscribed.controlled

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Long-press as a shortcut beside a visible path (the Edit toggle): never the only way. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(enabled: Boolean, label: String, onClick: () -> Unit, onLongClick: () -> Unit) =
    combinedClickable(enabled = enabled, onClickLabel = label, onLongClick = onLongClick, onClick = onClick)

@Composable
private fun SheetTitle(text: String, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        action()
    }
}

/**
 * Every app on the TV: pinned (the person's order), most used, then everything else A to Z. A long-press opens the app's
 * actions (pin, remove from Most used); Edit shows the same actions as visible badges. Removing offers Undo, never a
 * confirmation. A search field appears once there are more than 12 apps.
 */
@Composable
fun AppsSheet(model: RemoteModel, close: () -> Unit) {
    val t = LocalStrings.current
    val view = LocalView.current
    val tv = model.tv ?: return
    var editing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    /** The last removal from Most used, for Undo: the app and its launch count. */
    var removed by remember { mutableStateOf<Pair<TvApp, Int>?>(null) }
    LaunchedEffect(removed) {
        if (removed != null) {
            delay(5_000)
            removed = null
        }
    }
    val s = sections(model.apps, tv.pins, tv.uses)
    val matches = if (query.isBlank()) null else model.apps.filter { it.title.contains(query.trim(), ignoreCase = true) }
    val pin = { app: TvApp -> view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); model.togglePin(app.id) }
    val remove = { app: TvApp -> removed = app to model.forgetUses(app.id) }

    // Full height: a shorter sheet has room to move, and a fling past the grid's end bounces it up and back.
    Column(Modifier.fillMaxHeight()) {
        SheetTitle(t.apps) { TextButton({ editing = !editing }) { Text(if (editing) t.done else t.edit) } }
        if (editing) Text(t.editHint, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (model.apps.size > 12 && !editing) OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text(t.searchApps) }, singleLine = true, leadingIcon = { Icon(painterResource(R.drawable.sym_search), null) },
        )
        if (model.apps.isEmpty()) Text(t.appsLoading, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fun tiles(list: List<TvApp>, frequent: Boolean = false) = items(list, key = { it.id }) { app ->
                val pinned = app.id in tv.pins
                Box {
                    AppTile(
                        app, model,
                        Modifier.semantics {
                            if (pinned) stateDescription = t.pinned
                            customActions = listOfNotNull(
                                CustomAccessibilityAction(if (pinned) t.unpin(app.title) else t.pin(app.title)) { pin(app); true },
                                if (frequent) CustomAccessibilityAction(t.removeFromMostUsed) { remove(app); true } else null,
                            )
                        },
                        enabled = true, compact = false,
                        badge = when {
                            editing && frequent -> ({ RemoveBadge(t.removeFromMostUsed) { remove(app) } })
                            pinned || editing -> ({ PinBadge(pinned) })
                            else -> null
                        },
                        onLongClick = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); menuFor = app.id },
                    ) { if (editing) pin(app) else { model.launch(app); close() } }
                    DropdownMenu(menuFor == app.id, { menuFor = null }) {
                        DropdownMenuItem({ Text(if (pinned) t.unpin(app.title) else t.pin(app.title)) }, { menuFor = null; pin(app) },
                            leadingIcon = { Icon(painterResource(R.drawable.sym_keep), null) })
                        if (frequent) DropdownMenuItem({ Text(t.removeFromMostUsed) }, { menuFor = null; remove(app) },
                            leadingIcon = { Icon(painterResource(R.drawable.sym_close), null) })
                    }
                }
            }
            if (matches != null) {
                if (matches.isEmpty()) header(t.noAppsMatch(query.trim())) else tiles(matches)
            } else {
                if (s.pinned.isNotEmpty()) {
                    header(t.pinned)
                    if (editing) items(s.pinned, key = { "move-" + it.id }, span = { GridItemSpan(maxLineSpan) }) { app -> MoveRow(app, model) }
                    else tiles(s.pinned)
                }
                if (s.frequent.isNotEmpty()) { header(t.mostUsed); tiles(s.frequent, frequent = true) }
                if (s.rest.isNotEmpty()) { header(if (s.pinned.isEmpty() && s.frequent.isEmpty()) t.allApps else t.everythingElse); tiles(s.rest) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
        removed?.let { (app, count) ->
            Surface(Modifier.fillMaxWidth().padding(12.dp), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.inverseSurface) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.removedFromMostUsed(app.title), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.inverseOnSurface)
                    TextButton({ model.restoreUses(app.id, count); removed = null }, Modifier.height(48.dp)) {
                        Text(t.undo, color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }
    }
}

/** Edit mode's remove action on a Most used tile: a 22dp mark inside a 40dp touch area. */
@Composable
private fun RemoveBadge(label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.TopEnd) {
        Box(Modifier.padding(2.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.sym_close), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onError)
        }
    }
}

private fun LazyGridScope.header(text: String) = item(key = "h-$text", span = { GridItemSpan(maxLineSpan) }) {
    Text(text, Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PinBadge(pinned: Boolean) {
    Box(
        Modifier.padding(2.dp).size(22.dp).clip(CircleShape).background(if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.sym_keep), null, Modifier.size(14.dp), tint = if (pinned) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Editing pins: a row per pinned app with earlier and later, the accessible alternative to dragging. */
@Composable
private fun MoveRow(app: TvApp, model: RemoteModel) {
    val t = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(app, model, Modifier.size(40.dp))
        Text(app.title, Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
        IconButton({ model.movePin(app.id, -1) }) { Icon(painterResource(R.drawable.sym_keyboard_arrow_up), t.moveEarlier(app.title)) }
        IconButton({ model.movePin(app.id, 1) }) { Icon(painterResource(R.drawable.sym_keyboard_arrow_down), t.moveLater(app.title)) }
        IconButton({ model.togglePin(app.id) }) { Icon(painterResource(R.drawable.sym_close), t.unpin(app.title)) }
    }
}

/** The TV's inputs: connected first, the rest dimmed and saying so (never color alone). */
@Composable
fun InputsSheet(model: RemoteModel, close: () -> Unit) {
    val t = LocalStrings.current
    Column(Modifier.padding(bottom = 24.dp)) {
        SheetTitle(t.inputs)
        for (input in model.inputs) {
            val on = input.appId != null && input.appId == model.foreground
            Surface({ model.switchInput(input); close() }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).alpha(if (input.connected) 1f else 0.6f),
                shape = MaterialTheme.shapes.large, color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                ListItem(
                    headlineContent = { Text(input.label) },
                    supportingContent = when {
                        on -> ({ Text(t.onNow) })
                        !input.connected -> ({ Text(t.notConnected) })
                        else -> null
                    },
                    leadingContent = { Icon(painterResource(R.drawable.sym_input), null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

/** Type: what's typed here shows on the TV as it's typed (the model sends the difference). */
@Composable
fun KeyboardSheet(model: RemoteModel) {
    val t = LocalStrings.current
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SheetTitle(t.keyboard)
        OutlinedTextField(
            text, { new -> model.typeChange(text, new); text = new }, Modifier.fillMaxWidth().focusRequester(focus), label = { Text(t.typeHint) }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { model.enter() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Key(R.drawable.sym_backspace, t.deleteChar, repeat = true) { model.delete(); text = text.dropLast(1) }
            Key(R.drawable.sym_keyboard_return, t.enter, onClick = model::enter)
        }
    }
}

/** Drag moves the pointer (faster flicks travel farther), a tap clicks, two fingers scroll. */
@Composable
fun TouchpadSheet(model: RemoteModel, close: () -> Unit) {
    val t = LocalStrings.current
    val view = LocalView.current
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SheetTitle(t.touchpad)
        Box(
            Modifier.fillMaxWidth().height(340.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        var moved = 0f
                        // Sub-pixel remainders carry over, so slow drags still move the TV's pointer.
                        var carry = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent()
                            val down = event.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            val raw = down.fold(Offset.Zero) { acc, c -> acc + c.positionChange() } / down.size.toFloat()
                            moved += abs(raw.x) + abs(raw.y)
                            // Acceleration: slow moves are precise, quick flicks cross a 70" screen. Scroll stays linear.
                            val gain = if (down.size >= 2) 0.5f else (1f + raw.getDistance() / 12f).coerceAtMost(3f)
                            val delta = raw * gain + carry
                            val dx = delta.x.roundToInt()
                            val dy = delta.y.roundToInt()
                            carry = delta - Offset(dx.toFloat(), dy.toFloat())
                            if (dx != 0 || dy != 0) if (down.size >= 2) model.scroll(dx, dy) else model.move(dx, dy)
                            down.forEach { it.consume() }
                        }
                        // A tap that drifted less than the touch slop still clicks; a drag never does.
                        if (moved < viewConfiguration.touchSlop) {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            model.click()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(t.touchpadHint, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        TextButton(close, Modifier.height(48.dp)) { Text(t.close) }
    }
}
