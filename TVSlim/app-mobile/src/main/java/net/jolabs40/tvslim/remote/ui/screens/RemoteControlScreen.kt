package net.jolabs40.tvslim.remote.ui.screens

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.flow.Flow
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.control.RemoteKey
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ApplicationsState
import net.jolabs40.tvslim.remote.ui.HardwareKeyHost
import net.jolabs40.tvslim.remote.ui.RemoteControlUi
import net.jolabs40.tvslim.remote.ui.RemotePhase
import net.jolabs40.tvslim.remote.ui.ShortcutsUi
import net.jolabs40.tvslim.remote.ui.StartLightUi

/** What the remote control screen asks for. A press returns whether it went out, which decides the vibration. */
class RemoteControlActions(
    val onPress: (RemoteKey) -> Boolean,
    val onRelease: (RemoteKey) -> Unit,
    val onCancel: (RemoteKey) -> Unit,
    val onTap: (RemoteKey) -> Boolean,
    val onWake: () -> Boolean,
    val onSleep: () -> Boolean,
    val onText: (String) -> Boolean,
    val onRetry: () -> Unit,
    val onChooseTv: () -> Unit,
    val onClose: () -> Unit,
    val onLoadApps: () -> Unit,
    val onOpenApp: (DeviceApplication) -> Unit,
)

private enum class RemoteSheet { NONE, APPS, TEXT }

/**
 * Full-screen remote, laid out for one hand: rare actions at the top, the pad and the frequent keys where the thumb
 * rests. The phone's volume keys drive the TV's while it is shown, and the screen stays on.
 */
@Composable
fun RemoteControlScreen(
    tvName: String,
    state: RemoteControlUi,
    applications: ApplicationsState,
    rejected: Flow<Unit>,
    actions: RemoteControlActions,
    startLight: StartLightUi,
    startLightActions: StartLightActions,
    shortcuts: ShortcutsUi,
    shortcutActions: ShortcutActions,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(rejected) { rejected.collect { haptic.performHapticFeedback(HapticFeedbackType.Reject) } }
    VolumeKeys(actions, haptic)

    var sheet by rememberSaveable { mutableStateOf(RemoteSheet.NONE) }
    val ready = state.phase == RemotePhase.READY

    // StartLight's buttons have a page of their own, inside this screen so the link to the TV stays open.
    var startLightOpen by rememberSaveable { mutableStateOf(false) }
    val startLightShown = ready && startLight.home != null
    if (startLightOpen && startLightShown) {
        BackHandler { startLightOpen = false }
        StartLightPage(startLight, startLightActions, onClose = { startLightOpen = false })
        return
    }

    // Choosing the shortcuts too: a page inside this screen, for the same reason.
    var shortcutsOpen by rememberSaveable { mutableStateOf(false) }
    if (shortcutsOpen && shortcuts.host != null) {
        BackHandler { shortcutsOpen = false }
        ShortcutsPage(
            shortcuts = shortcuts.shortcuts,
            startLight = startLight,
            applications = applications,
            onLoadApps = actions.onLoadApps,
            onToggle = shortcutActions.onToggle,
            onClose = { shortcutsOpen = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(tvName, state, actions)
        // Between the header and the keys, scrolling on its own so the keys never leave the thumb's reach.
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Problem(state, actions)
            // Above the banner, which comes once StartLight is found: the shortcuts do not move under the thumb.
            if (shortcuts.host != null) {
                ShortcutsGrid(
                    shortcuts = shortcuts.shortcuts,
                    ready = ready,
                    startLight = startLight,
                    actions = shortcutActions,
                    onOpenApp = actions.onOpenApp,
                    onStartLight = startLightActions.onCommand,
                    onAdd = { shortcutsOpen = true },
                )
            }
            if (startLightShown) StartLightBanner(startLight, onOpen = { startLightOpen = true })
        }
        Column(
            modifier = Modifier.alpha(if (ready) 1f else 0.5f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KeyRow {
                RoundKey(RemoteKey.PREVIOUS, R.string.remote_previous, Icons.Filled.SkipPrevious, actions, haptic)
                RoundKey(RemoteKey.PLAY_PAUSE, R.string.remote_play_pause, null, actions, haptic) {
                    Row {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
                RoundKey(RemoteKey.NEXT, R.string.remote_next, Icons.Filled.SkipNext, actions, haptic)
            }
            Spacer(Modifier.height(12.dp))
            KeyRow {
                RoundKey(RemoteKey.VOLUME_DOWN, R.string.remote_volume_down, Icons.AutoMirrored.Filled.VolumeDown, actions, haptic)
                RoundKey(RemoteKey.MUTE, R.string.remote_mute, Icons.AutoMirrored.Filled.VolumeOff, actions, haptic)
                RoundKey(RemoteKey.VOLUME_UP, R.string.remote_volume_up, Icons.AutoMirrored.Filled.VolumeUp, actions, haptic)
            }
            Spacer(Modifier.height(24.dp))
            DirectionPad(actions, haptic)
            Spacer(Modifier.height(24.dp))
            KeyRow {
                RoundKey(RemoteKey.BACK, R.string.remote_back, Icons.AutoMirrored.Filled.ArrowBack, actions, haptic)
                RoundKey(RemoteKey.HOME, R.string.remote_home, Icons.Filled.Home, actions, haptic)
                SheetButton(R.string.remote_apps, Icons.Filled.Apps) { sheet = RemoteSheet.APPS }
                SheetButton(R.string.remote_keyboard, Icons.Filled.Keyboard) { sheet = RemoteSheet.TEXT }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    when (sheet) {
        RemoteSheet.APPS -> AppsSheet(applications, actions) { sheet = RemoteSheet.NONE }
        RemoteSheet.TEXT -> TextSheet(state.canPaste, actions, haptic) { sheet = RemoteSheet.NONE }
        RemoteSheet.NONE -> Unit
    }
}

@Composable
private fun Header(tvName: String, state: RemoteControlUi, actions: RemoteControlActions) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remote_close))
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = tvName.ifBlank { stringResource(R.string.remote_open) },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == RemotePhase.READY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val asleep = state.screenOn == false
        FilledTonalIconButton(
            onClick = { if (asleep) actions.onWake() else actions.onSleep() },
            enabled = state.phase == RemotePhase.READY,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                contentColor = if (asleep) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
            ),
        ) {
            Icon(
                Icons.Filled.PowerSettingsNew,
                contentDescription = stringResource(if (asleep) R.string.remote_wake else R.string.remote_sleep),
            )
        }
    }
}

@Composable
private fun status(state: RemoteControlUi): String = when (state.phase) {
    RemotePhase.NO_TV -> stringResource(R.string.remote_status_no_tv)
    RemotePhase.CONNECTING -> stringResource(R.string.remote_status_connecting)
    RemotePhase.RECONNECTING -> stringResource(R.string.remote_status_reconnecting)
    RemotePhase.UNREACHABLE -> stringResource(R.string.remote_status_unreachable)
    RemotePhase.READY -> when {
        state.screenOn == false -> stringResource(R.string.remote_status_asleep)
        state.latencyMs != null -> stringResource(R.string.remote_status_latency, state.latencyMs.toInt())
        else -> stringResource(R.string.remote_status_ready)
    }
}

/** Said in words rather than left spinning: a box that cut its network in standby will not come back by itself. */
@Composable
private fun Problem(state: RemoteControlUi, actions: RemoteControlActions) {
    val text = when (state.phase) {
        RemotePhase.NO_TV -> R.string.remote_no_tv
        RemotePhase.UNREACHABLE -> if (state.wentToSleep) R.string.remote_unreachable_asleep else R.string.remote_unreachable
        else -> return
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onRetry) { Text(stringResource(R.string.remote_retry)) }
                if (state.phase == RemotePhase.NO_TV) {
                    OutlinedButton(onClick = actions.onChooseTv) { Text(stringResource(R.string.remote_choose_tv)) }
                }
            }
        }
    }
}

@Composable
private fun KeyRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * Presses on touch and releases on lift, so a held key repeats on the TV. Sliding off the key, or the screen going
 * away mid-press, cancels it: the TV then neither clicks nor goes back. Keys that are not [RemoteKey.holdable] are
 * sent as one tap. TalkBack's activation sends a tap.
 */
private fun Modifier.remoteKey(
    key: RemoteKey,
    description: String,
    actions: () -> RemoteControlActions,
    haptic: HapticFeedback,
    onPressed: (Boolean) -> Unit,
): Modifier = semantics {
    contentDescription = description
    role = Role.Button
    onClick {
        actions().onTap(key)
        true
    }
}.pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        val sent = if (key.holdable) actions().onPress(key) else actions().onTap(key)
        if (sent) haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        if (!key.holdable || !sent) {
            waitForUpOrCancellation()?.consume()
            return@awaitEachGesture
        }
        onPressed(true)
        var ended = false
        try {
            val up = waitForUpOrCancellation()
            ended = true
            if (up != null) {
                up.consume()
                actions().onRelease(key)
            } else {
                actions().onCancel(key)
            }
        } finally {
            if (!ended) actions().onCancel(key)
            onPressed(false)
        }
    }
}

@Composable
private fun KeySurface(
    key: RemoteKey,
    description: Int,
    actions: RemoteControlActions,
    haptic: HapticFeedback,
    modifier: Modifier,
    shape: Shape,
    idle: Color,
    content: @Composable () -> Unit,
) {
    val current by rememberUpdatedState(actions)
    var pressed by remember { mutableStateOf(false) }
    val label = stringResource(description)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) MaterialTheme.colorScheme.primaryContainer else idle)
            .remoteKey(key, label, { current }, haptic) { pressed = it },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RoundKey(
    key: RemoteKey,
    description: Int,
    icon: ImageVector?,
    actions: RemoteControlActions,
    haptic: HapticFeedback,
    content: (@Composable () -> Unit)? = null,
) {
    KeySurface(
        key = key,
        description = description,
        actions = actions,
        haptic = haptic,
        modifier = Modifier.size(60.dp),
        shape = CircleShape,
        idle = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (content != null) content() else if (icon != null) Icon(icon, contentDescription = null)
    }
}

@Composable
private fun SheetButton(description: Int, icon: ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(description), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Three by three cells inside a disc: each arrow is its own button, large enough for a thumb. */
@Composable
private fun DirectionPad(actions: RemoteControlActions, haptic: HapticFeedback) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val side: Dp = min(maxWidth * 0.82f, 300.dp)
        Box(
            modifier = Modifier
                .size(side)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            val cell = side / 3
            Column {
                Row {
                    Spacer(Modifier.size(cell))
                    Arrow(RemoteKey.UP, R.string.remote_up, 0f, cell, actions, haptic)
                    Spacer(Modifier.size(cell))
                }
                Row {
                    Arrow(RemoteKey.LEFT, R.string.remote_left, 270f, cell, actions, haptic)
                    Box(modifier = Modifier.size(cell), contentAlignment = Alignment.Center) {
                        KeySurface(
                            key = RemoteKey.CENTER,
                            description = R.string.remote_ok,
                            actions = actions,
                            haptic = haptic,
                            modifier = Modifier.size(cell - 8.dp),
                            shape = CircleShape,
                            idle = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                text = stringResource(R.string.remote_ok),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Arrow(RemoteKey.RIGHT, R.string.remote_right, 90f, cell, actions, haptic)
                }
                Row {
                    Spacer(Modifier.size(cell))
                    Arrow(RemoteKey.DOWN, R.string.remote_down, 180f, cell, actions, haptic)
                    Spacer(Modifier.size(cell))
                }
            }
        }
    }
}

/** One glyph rotated rather than the left and right arrows, which mirror in right-to-left languages. */
@Composable
private fun Arrow(key: RemoteKey, description: Int, rotation: Float, cell: Dp, actions: RemoteControlActions, haptic: HapticFeedback) {
    KeySurface(
        key = key,
        description = description,
        actions = actions,
        haptic = haptic,
        modifier = Modifier.size(cell),
        shape = RoundedCornerShape(cell / 3),
        idle = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            modifier = Modifier.size(40.dp).rotate(rotation),
        )
    }
}

/** The phone's volume keys drive the TV's, only while the remote is on screen. */
@Composable
private fun VolumeKeys(actions: RemoteControlActions, haptic: HapticFeedback) {
    val host = LocalActivity.current as? HardwareKeyHost ?: return
    val current by rememberUpdatedState(actions)
    DisposableEffect(host) {
        host.hardwareKeys = handler@{ event ->
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> RemoteKey.VOLUME_UP
                KeyEvent.KEYCODE_VOLUME_DOWN -> RemoteKey.VOLUME_DOWN
                else -> return@handler false
            }
            when (event.action) {
                // The phone's own repeats are ignored: the TV repeats a held key at its own cadence.
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0 && current.onPress(key)) {
                    haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                }

                KeyEvent.ACTION_UP -> if (event.isCanceled) current.onCancel(key) else current.onRelease(key)
            }
            true
        }
        onDispose { host.hardwareKeys = null }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsSheet(applications: ApplicationsState, actions: RemoteControlActions, onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        if (!applications.loaded && !applications.loading) actions.onLoadApps()
    }
    val openable = applications.applications.filter { it.launch != null && it.active }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.remote_apps_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            when {
                openable.isEmpty() && applications.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.remote_apps_loading))
                }

                openable.isEmpty() -> Text(stringResource(R.string.remote_apps_empty))
                else -> LazyVerticalGrid(columns = GridCells.Adaptive(88.dp)) {
                    items(openable, key = { it.packageName }) { application ->
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    actions.onOpenApp(application)
                                    onDismiss()
                                }
                                .padding(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ApplicationIcon(application, 48)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = application.name,
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(canPaste: Boolean, actions: RemoteControlActions, haptic: HapticFeedback, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        if (actions.onText(text)) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            text = ""
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.remote_text_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.remote_text_hint)) },
                // Enter sends, as the keyboard's action key does; the ↵ key sends Enter to the TV.
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            if (!canPaste) {
                Text(
                    text = stringResource(R.string.remote_text_no_paste),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundKey(RemoteKey.DELETE, R.string.remote_text_delete, Icons.AutoMirrored.Filled.Backspace, actions, haptic)
                RoundKey(RemoteKey.ENTER, R.string.remote_text_enter, Icons.AutoMirrored.Filled.KeyboardReturn, actions, haptic)
                Spacer(Modifier.weight(1f))
                Button(onClick = send, enabled = text.isNotEmpty()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_text_send))
                }
            }
        }
    }
}
