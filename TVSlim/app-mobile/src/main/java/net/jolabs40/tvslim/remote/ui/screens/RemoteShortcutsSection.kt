package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.data.RemoteShortcut
import net.jolabs40.tvslim.remote.data.RemoteShortcuts
import net.jolabs40.tvslim.remote.ui.ApplicationsState
import net.jolabs40.tvslim.remote.ui.StartLightUi
import net.jolabs40.tvslim.startlight.StartLightAction
import net.jolabs40.tvslim.startlight.StartLightHome
import net.jolabs40.tvslim.startlight.StartLightState

class ShortcutActions(
    val onToggle: (RemoteShortcut) -> Unit,
    val onMove: (index: Int, by: Int) -> Unit,
    val onRemove: (index: Int) -> Unit,
)

/**
 * The person's own shortcuts, as round icons between the pad and the keys below: apps of the TV and StartLight's
 * buttons. One that cannot act right now is greyed rather than hidden, so the others never move under the thumb. A
 * long press names it, moves it or removes it. More than the width holds scroll sideways.
 */
@Composable
internal fun ShortcutsRow(
    shortcuts: List<RemoteShortcut>,
    ready: Boolean,
    startLight: StartLightUi,
    actions: ShortcutActions,
    onOpenApp: (DeviceApplication) -> Unit,
    onStartLight: (RemoteShortcut) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        itemsIndexed(shortcuts) { index, shortcut ->
            ShortcutButton(
                shortcut = shortcut,
                index = index,
                count = shortcuts.size,
                ready = ready,
                startLight = startLight,
                actions = actions,
                onOpenApp = onOpenApp,
                onStartLight = onStartLight,
                haptic = haptic,
            )
        }
    }
}

@Composable
private fun ShortcutButton(
    shortcut: RemoteShortcut,
    index: Int,
    count: Int,
    ready: Boolean,
    startLight: StartLightUi,
    actions: ShortcutActions,
    onOpenApp: (DeviceApplication) -> Unit,
    onStartLight: (RemoteShortcut) -> Unit,
    haptic: HapticFeedback,
) {
    val enabled = ready && when (shortcut) {
        is RemoteShortcut.Application -> true
        // Behind another app, HOME brings StartLight first. Not running, what it offers is known once HOME starts it.
        else -> startLight.reachable && (startLight.state == null || shortcut.startLightCommand(startLight.state) != null)
    }
    val label = shortcutLabel(shortcut, startLight.state)
    var menu by remember { mutableStateOf(false) }
    // A layer always there: alpha(1f) drops it, and the tile lost its background when it came back on. Without the
    // link, the keys around already dim it.
    Box(modifier = Modifier.graphicsLayer { alpha = if (enabled || !ready) 1f else 0.4f }) {
        Box(
            modifier = Modifier
                .size(SHORTCUT_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .semantics { contentDescription = label }
                .combinedClickable(
                    onLongClickLabel = stringResource(R.string.remote_shortcuts_edit),
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                    onClick = {
                        if (!enabled) {
                            haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        } else {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            when (shortcut) {
                                is RemoteShortcut.Application -> onOpenApp(shortcut.application())
                                else -> onStartLight(shortcut)
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            ShortcutIcon(shortcut, startLight.state)
        }
        if (shortcut !is RemoteShortcut.Application) {
            // Ringed in the page's colour, so it reads as a badge on the circle's edge.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(2.dp),
            ) {
                LogoLauncher(StartLightHome.LAUNCHER_ID, size = 14.dp, modifier = Modifier.clip(CircleShape))
            }
        }
        // Earlier is to the left, except in a right-to-left language.
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            // The remote shows no name: the menu says which shortcut it is about.
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            if (index > 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (rtl) R.string.remote_shortcuts_move_right else R.string.remote_shortcuts_move_left)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.onMove(index, -1)
                    },
                )
            }
            if (index < count - 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (rtl) R.string.remote_shortcuts_move_left else R.string.remote_shortcuts_move_right)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    onClick = {
                        menu = false
                        actions.onMove(index, 1)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_shortcuts_remove)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    menu = false
                    actions.onRemove(index)
                },
            )
        }
    }
}

@Composable
private fun ShortcutIcon(shortcut: RemoteShortcut, state: StartLightState?) {
    when (shortcut) {
        is RemoteShortcut.Application -> {
            val application = remember(shortcut) { shortcut.application() }
            Box(modifier = Modifier.clip(CircleShape)) { ApplicationIcon(application, APP_ICON_SIZE) }
        }

        else -> Icon(
            imageVector = startLightIcon(shortcut, state),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
    }
}

private fun startLightIcon(shortcut: RemoteShortcut, state: StartLightState?): ImageVector = when (shortcut) {
    is RemoteShortcut.Action -> StartLightAction.entries.firstOrNull { it.wire == shortcut.action }?.let(::actionIcon) ?: Icons.Filled.TouchApp
    is RemoteShortcut.Page -> if (state?.pages?.firstOrNull { it.id == shortcut.id }?.home == true) Icons.Filled.Home else Icons.AutoMirrored.Filled.ViewQuilt
    is RemoteShortcut.Dashboard -> Icons.Filled.Dashboard
    is RemoteShortcut.Application -> Icons.Filled.TouchApp
}

/** StartLight's current title for a page, when it has one: a page renamed on the TV follows. */
@Composable
private fun shortcutLabel(shortcut: RemoteShortcut, state: StartLightState?): String = when (shortcut) {
    is RemoteShortcut.Application -> shortcut.name
    is RemoteShortcut.Action -> StartLightAction.entries.firstOrNull { it.wire == shortcut.action }
        ?.let { stringResource(actionLabel(it)) }
        ?: shortcut.action
    is RemoteShortcut.Page -> state?.pages?.firstOrNull { it.id == shortcut.id }?.title?.takeIf { it.isNotBlank() }
        ?: shortcut.title.ifBlank { shortcut.id }
    is RemoteShortcut.Dashboard -> state?.dashboards?.firstOrNull { it.id == shortcut.id }?.title?.takeIf { it.isNotBlank() }
        ?: shortcut.title.ifBlank { shortcut.id }
}

/**
 * Choosing the shortcuts: StartLight's buttons, when it is the home, then the TV's apps. A touch pins or unpins;
 * the order is the order of pinning, changed on the remote by a long press.
 */
@Composable
internal fun ShortcutsPage(
    shortcuts: List<RemoteShortcut>,
    startLight: StartLightUi,
    applications: ApplicationsState,
    onLoadApps: () -> Unit,
    onToggle: (RemoteShortcut) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (!applications.loaded && !applications.loading) onLoadApps()
    }
    val haptic = LocalHapticFeedback.current
    val pinned = remember(shortcuts) { shortcuts.mapTo(HashSet()) { it.target } }
    val toggle = { shortcut: RemoteShortcut ->
        haptic.performHapticFeedback(if (shortcut.target in pinned) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        onToggle(shortcut)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.remote_settings_return))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(stringResource(R.string.remote_shortcuts_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(R.string.remote_shortcuts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val state = startLight.state
        if (startLight.home != null && state != null) StartLightChoices(state, pinned, toggle)

        SectionTitle(stringResource(R.string.remote_shortcuts_apps))
        val openable = applications.applications.filter { it.launch != null && it.active }
        when {
            openable.isEmpty() && applications.loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.remote_apps_loading))
            }

            openable.isEmpty() -> Text(
                text = stringResource(R.string.remote_apps_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> Grid(openable.mapNotNull { RemoteShortcut.Application.of(it)?.let { shortcut -> it to shortcut } }, GRID_COLUMNS) { (application, shortcut), modifier ->
                ChoiceTile(application.name, shortcut.target in pinned, { toggle(shortcut) }, modifier) {
                    ApplicationIcon(application, 40)
                }
            }
        }
    }
}

@Composable
private fun StartLightChoices(state: StartLightState, pinned: Set<String>, toggle: (RemoteShortcut) -> Unit) {
    val actions = RemoteShortcuts.pinnableActions(state)
    val pages = state.offeredPages
    val dashboards = state.offeredDashboards
    if (actions.isEmpty() && pages.isEmpty() && dashboards.isEmpty()) return

    Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoLauncher(StartLightHome.LAUNCHER_ID, size = 20.dp)
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.startlight_name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
    Grid(actions, ACTION_COLUMNS) { action, modifier ->
        val shortcut = RemoteShortcut.Action(action.wire)
        ChoiceTile(stringResource(actionLabel(action)), shortcut.target in pinned, { toggle(shortcut) }, modifier) {
            Icon(actionIcon(action), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
    }
    if (pages.isNotEmpty()) {
        Column {
            pages.forEach { page ->
                val shortcut = RemoteShortcut.Page(page.id, page.title)
                ChoiceLine(page.title.ifBlank { page.id }, if (page.home) Icons.Filled.Home else Icons.AutoMirrored.Filled.ViewQuilt, shortcut.target in pinned) {
                    toggle(shortcut)
                }
            }
        }
    }
    if (dashboards.isNotEmpty()) {
        Column {
            dashboards.forEach { dashboard ->
                val shortcut = RemoteShortcut.Dashboard(dashboard.id, dashboard.title)
                ChoiceLine(dashboard.title.ifBlank { dashboard.id }, Icons.Filled.Dashboard, shortcut.target in pinned) {
                    toggle(shortcut)
                }
            }
        }
    }
}

@Composable
private fun <T> Grid(items: List<T>, columns: Int, cell: @Composable (T, Modifier) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { item -> cell(item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceTile(label: String, pinned: Boolean, onToggle: () -> Unit, modifier: Modifier, icon: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (pinned) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .toggleable(value = pinned, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            icon()
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (pinned) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp),
            )
        }
    }
}

@Composable
private fun ChoiceLine(text: String, icon: ImageVector, pinned: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = pinned, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Checkbox(checked = pinned, onCheckedChange = null)
    }
    HorizontalDivider()
}

private const val GRID_COLUMNS = 4

/** Smaller than the keys around them, which the thumb looks for without looking. */
internal val SHORTCUT_SIZE = 52.dp

/** A disc inside the button rather than a square in a circle, small enough that an icon drawn on transparency keeps its tips. */
private const val APP_ICON_SIZE = 44
private const val ACTION_COLUMNS = 3
