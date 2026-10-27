package dev.unsubscribed.controlled

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The controller's name at the top of its screen, with ▾: a tap opens Your controllers. */
@Composable
fun ControllerTitle(name: String, open: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalStrings.current
    Row(
        modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClickLabel = t.switchController, onClick = open)
            .heightIn(min = 40.dp).semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(painterResource(R.drawable.sym_arrow_drop_down), null)
    }
}

/** Your controllers: one tap opens one, the star marks the one the app opens on; each has Rename, Make default and Remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllersSheet(model: RemoteModel, close: () -> Unit) {
    val t = LocalStrings.current
    var renaming by remember { mutableStateOf<Controller?>(null) }
    ModalBottomSheet(close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle(t.controllers)
            val current = model.tv?.id ?: model.pc?.id
            for (c in model.controllers.all) {
                ControllerRow(c, current = c.id == current, default = c.id == model.controllers.defaultOne()?.id,
                    open = { model.select(c); close() }, rename = { renaming = c }, makeDefault = { model.setDefault(c) }, remove = { model.remove(c) })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton({ model.addTv(); close() }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.sym_tv), null)
                    Text(t.addTv, Modifier.padding(start = 8.dp))
                }
                if (model.hid != null) FilledTonalButton({ model.addPc(); close() }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(painterResource(R.drawable.sym_computer), null)
                    Text(t.addPc, Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    renaming?.let { c -> RenameDialog(c.name, { renaming = null }, pc = c is PairedPc) { model.renameController(c, it); renaming = null } }
}

@Composable
private fun ControllerRow(c: Controller, current: Boolean, default: Boolean, open: () -> Unit, rename: () -> Unit, makeDefault: () -> Unit, remove: () -> Unit) {
    val t = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = open,
        modifier = Modifier.padding(horizontal = 8.dp).semantics { selected = current },
        shape = MaterialTheme.shapes.large,
        color = if (current) colors.secondaryContainer else colors.surfaceContainerLow,
    ) {
        ListItem(
            leadingContent = { Icon(painterResource(if (c is PairedPc) R.drawable.sym_computer else R.drawable.sym_tv), null) },
            headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            // The default says so in words, not only with the star.
            supportingContent = if (default) ({ Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.sym_star), null, Modifier.size(16.dp), tint = colors.primary)
                Text(t.defaultController, Modifier.padding(start = 4.dp))
            } }) else null,
            trailingContent = {
                Box {
                    IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(painterResource(R.drawable.sym_more_vert), t.more) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(t.rename) }, { menu = false; rename() })
                        if (!default) DropdownMenuItem({ Text(t.makeDefault) }, { menu = false; makeDefault() },
                            leadingIcon = { Icon(painterResource(R.drawable.sym_star), null) })
                        DropdownMenuItem({ Text(t.remove) }, { menu = false; remove() }, leadingIcon = { Icon(painterResource(R.drawable.sym_link_off), null) })
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    Spacer(Modifier.height(4.dp))
}
