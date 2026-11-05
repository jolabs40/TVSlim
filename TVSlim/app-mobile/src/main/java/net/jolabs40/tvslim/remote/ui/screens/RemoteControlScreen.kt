package net.jolabs40.tvslim.remote.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.applications.DeviceApplication
import net.jolabs40.tvslim.control.RemoteKey
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ApplicationsState
import net.jolabs40.tvslim.remote.ui.HardwareKeyHost
import net.jolabs40.tvslim.remote.ui.RemoteControlUi
import net.jolabs40.tvslim.remote.ui.RemotePhase
import net.jolabs40.tvslim.remote.ui.ShortcutsUi
import net.jolabs40.tvslim.remote.ui.StartLightUi
import net.jolabs40.tvslim.remote.ui.theme.RiskColors

/** What the remote control screen asks for. A press returns whether it went out, which decides the vibration. */
class RemoteControlActions(
    val onPress: (RemoteKey) -> Boolean,
    val onRelease: (RemoteKey) -> Unit,
    val onCancel: (RemoteKey) -> Unit,
    val onTap: (RemoteKey) -> Boolean,
    val onWake: () -> Boolean,
    val onSleep: () -> Boolean,
    val onText: (String) -> Boolean,
    /** Moves the TV's pointer and scrolls, in mouse counts and wheel notches. */
    val onPointer: (dx: Int, dy: Int, wheel: Int) -> Boolean,
    val onPointerClick: () -> Boolean,
    val onRetry: () -> Unit,
    val onChooseTv: () -> Unit,
    val onClose: () -> Unit,
    val onLoadApps: () -> Unit,
    val onOpenApp: (DeviceApplication) -> Unit,
)

private enum class RemoteSheet { NONE, APPS, TEXT, DIGITS }

/** Pages inside this screen rather than routes: leaving the route would stop the remote's entry, and its link. */
private enum class RemotePage { KEYS, SETTINGS, STARTLIGHT, SHORTCUTS }

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
    openAtLaunch: Boolean,
    onOpenAtLaunch: (Boolean) -> Unit,
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

    // StartLight's page and the choice of shortcuts are reached through the settings, and go back to them.
    var page by rememberSaveable { mutableStateOf(RemotePage.KEYS) }
    val startLightShown = ready && startLight.home != null
    when {
        page == RemotePage.SETTINGS -> {
            BackHandler { page = RemotePage.KEYS }
            RemoteSettingsPage(
                openAtLaunch = openAtLaunch,
                onOpenAtLaunch = onOpenAtLaunch,
                startLight = startLight.takeIf { startLightShown },
                shortcutsShown = shortcuts.host != null,
                onOpenStartLight = { page = RemotePage.STARTLIGHT },
                onChooseShortcuts = { page = RemotePage.SHORTCUTS },
                onClose = { page = RemotePage.KEYS },
            )
            return
        }

        page == RemotePage.STARTLIGHT && startLightShown -> {
            BackHandler { page = RemotePage.SETTINGS }
            StartLightPage(startLight, startLightActions, onClose = { page = RemotePage.SETTINGS })
            return
        }

        page == RemotePage.SHORTCUTS && shortcuts.host != null -> {
            BackHandler { page = RemotePage.SETTINGS }
            ShortcutsPage(
                shortcuts = shortcuts.shortcuts,
                startLight = startLight,
                applications = applications,
                onLoadApps = actions.onLoadApps,
                onToggle = shortcutActions.onToggle,
                onClose = { page = RemotePage.SETTINGS },
            )
            return
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header(tvName, state, actions, onSettings = { page = RemotePage.SETTINGS })
        // Room for StartLight's notice, kept empty while the link is up so the keys never shrink under the thumb when
        // it comes. Without the link, it grows to the problem and the keys, inactive anyway, give way.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NOTICE_ROOM, max = if (ready) NOTICE_ROOM else Dp.Unspecified)
                .verticalScroll(rememberScrollState()),
        ) {
            Problem(state, actions)
            if (startLightShown) StartLightNoticeCard(startLight)
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            val withShortcuts = shortcuts.host != null && shortcuts.shortcuts.isNotEmpty()
            // Sideways, the height holds the pad alone: the other keys go beside it.
            val sideways = maxWidth > maxHeight
            val metrics = if (sideways) {
                sidewaysKeyMetrics(maxWidth, maxHeight, withShortcuts, state.pointer)
            } else {
                keyMetrics(maxWidth, maxHeight, withShortcuts, state.pointer)
            }
            val scale = metrics.keyScale
            val mediaKeys: @Composable () -> Unit = {
                KeyRow {
                    DigitsButton(scale) { sheet = RemoteSheet.DIGITS }
                    RoundKey(RemoteKey.PREVIOUS, R.string.remote_previous, Icons.Filled.SkipPrevious, actions, haptic, scale)
                    RoundKey(RemoteKey.PLAY_PAUSE, R.string.remote_play_pause, null, actions, haptic, scale) {
                        Row {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp * scale))
                            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(20.dp * scale))
                        }
                    }
                    RoundKey(RemoteKey.NEXT, R.string.remote_next, Icons.Filled.SkipNext, actions, haptic, scale)
                }
            }
            val pad: @Composable (Modifier) -> Unit = { modifier ->
                if (state.pointer) PadPager(metrics.pad, actions, haptic, modifier) else DirectionPad(metrics.pad, actions, haptic)
            }
            val shortcutsRow: @Composable () -> Unit = {
                ShortcutsRow(
                    shortcuts = shortcuts.shortcuts,
                    ready = ready,
                    startLight = startLight,
                    actions = shortcutActions,
                    onOpenApp = actions.onOpenApp,
                    onStartLight = { shortcut -> startLightActions.onInFront(shortcut::startLightCommand) },
                )
            }
            val bottomKeys: @Composable () -> Unit = {
                val rowGap = metrics.gap(ROW_GAP)
                val pillHeight = KEY_SIZE * scale * 2 + rowGap
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    KeyPill(
                        up = RemoteKey.VOLUME_UP,
                        upDescription = R.string.remote_volume_up,
                        upIcon = Icons.Filled.Add,
                        down = RemoteKey.VOLUME_DOWN,
                        downDescription = R.string.remote_volume_down,
                        downIcon = Icons.Filled.Remove,
                        label = stringResource(R.string.remote_volume_label),
                        scale = scale,
                        height = pillHeight,
                        actions = actions,
                        haptic = haptic,
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(rowGap)) {
                        KeyRow {
                            SheetButton(R.string.remote_keyboard, Icons.Filled.Keyboard, scale) { sheet = RemoteSheet.TEXT }
                            RoundKey(RemoteKey.HOME, R.string.remote_home, Icons.Filled.Home, actions, haptic, scale)
                            SheetButton(R.string.remote_apps, Icons.Filled.Apps, scale) { sheet = RemoteSheet.APPS }
                        }
                        KeyRow {
                            RoundKey(RemoteKey.MUTE, R.string.remote_mute, Icons.AutoMirrored.Filled.VolumeOff, actions, haptic, scale)
                            VoiceButton(scale, ready, actions, haptic)
                            RoundKey(RemoteKey.BACK, R.string.remote_back, Icons.AutoMirrored.Filled.ArrowBack, actions, haptic, scale)
                        }
                    }
                    KeyPill(
                        up = RemoteKey.CHANNEL_UP,
                        upDescription = R.string.remote_channel_up,
                        upIcon = Icons.Filled.KeyboardArrowUp,
                        down = RemoteKey.CHANNEL_DOWN,
                        downDescription = R.string.remote_channel_down,
                        downIcon = Icons.Filled.KeyboardArrowDown,
                        label = stringResource(R.string.remote_channel_label),
                        scale = scale,
                        height = pillHeight,
                        actions = actions,
                        haptic = haptic,
                    )
                }
            }
            val dimmed = Modifier.alpha(if (ready) 1f else 0.5f)
            if (sideways) {
                Row(
                    modifier = dimmed.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(PAD_GAP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) { bottomKeys() }
                    pad(Modifier.width(metrics.pad))
                    Column(modifier = Modifier.weight(1f)) {
                        mediaKeys()
                        if (withShortcuts) {
                            Spacer(Modifier.height(metrics.gap(SHORTCUTS_GAP)))
                            shortcutsRow()
                        }
                    }
                }
            } else {
                Column(modifier = dimmed, horizontalAlignment = Alignment.CenterHorizontally) {
                    mediaKeys()
                    Spacer(Modifier.height(metrics.gap(MEDIA_GAP)))
                    if (withShortcuts) {
                        shortcutsRow()
                        Spacer(Modifier.height(metrics.gap(SHORTCUTS_GAP)))
                    }
                    pad(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(metrics.gap(PAD_GAP)))
                    bottomKeys()
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    when (sheet) {
        RemoteSheet.APPS -> AppsSheet(applications, actions) { sheet = RemoteSheet.NONE }
        RemoteSheet.TEXT -> TextSheet(state.canPaste, actions, haptic) { sheet = RemoteSheet.NONE }
        RemoteSheet.DIGITS -> DigitsSheet(actions, haptic) { sheet = RemoteSheet.NONE }
        RemoteSheet.NONE -> Unit
    }
}

/** The TV's name opens the choice of another, as the arrow beside it says. */
@Composable
private fun Header(tvName: String, state: RemoteControlUi, actions: RemoteControlActions, onSettings: () -> Unit) {
    val asleep = state.screenOn == false
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remote_close))
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.remote_settings))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.remote_choose_tv), onClick = actions.onChooseTv)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val linked = state.phase == RemotePhase.READY && !asleep
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tvName.ifBlank { stringResource(R.string.remote_open) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            Text(
                text = status(state),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { if (asleep) actions.onWake() else actions.onSleep() },
            enabled = state.phase == RemotePhase.READY,
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (asleep) MaterialTheme.colorScheme.outline else RiskColors.high,
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
    label: String,
    actions: RemoteControlActions,
    haptic: HapticFeedback,
    modifier: Modifier,
    shape: Shape,
    idle: Color,
    content: @Composable () -> Unit,
) {
    val current by rememberUpdatedState(actions)
    var pressed by remember { mutableStateOf(false) }
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
    scale: Float = 1f,
    content: (@Composable () -> Unit)? = null,
) {
    KeySurface(
        key = key,
        label = stringResource(description),
        actions = actions,
        haptic = haptic,
        modifier = Modifier.size(KEY_SIZE * scale),
        shape = CircleShape,
        idle = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (content != null) {
            content()
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(KEY_ICON_SIZE * scale))
        }
    }
}

@Composable
private fun SheetButton(description: Int, icon: ImageVector, scale: Float, onClick: () -> Unit) {
    SheetButton(scale, onClick) {
        Icon(icon, contentDescription = stringResource(description), modifier = Modifier.size(KEY_ICON_SIZE * scale))
    }
}

/** Bears digits rather than an icon, and is read by its name. */
@Composable
private fun DigitsButton(scale: Float, onClick: () -> Unit) {
    val name = stringResource(R.string.remote_digits)
    SheetButton(scale, onClick) {
        Text(
            text = stringResource(R.string.remote_digits_label),
            modifier = Modifier.clearAndSetSemantics { contentDescription = name },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Looks like the keys beside it, though it opens a sheet rather than sending one. */
@Composable
private fun SheetButton(scale: Float, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(KEY_SIZE * scale)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Two keys in one tall shape, as on a TV's own remote. The label sits between them and lets touches through. */
@Composable
private fun KeyPill(
    up: RemoteKey,
    upDescription: Int,
    upIcon: ImageVector,
    down: RemoteKey,
    downDescription: Int,
    downIcon: ImageVector,
    label: String?,
    scale: Float,
    height: Dp,
    actions: RemoteControlActions,
    haptic: HapticFeedback,
) {
    val width = KEY_SIZE * scale
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(RoundedCornerShape(width / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column {
            for ((key, description, icon) in listOf(Triple(up, upDescription, upIcon), Triple(down, downDescription, downIcon))) {
                KeySurface(
                    key = key,
                    label = stringResource(description),
                    actions = actions,
                    haptic = haptic,
                    modifier = Modifier.size(width, height / 2),
                    shape = RectangleShape,
                    idle = Color.Transparent,
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(KEY_ICON_SIZE * scale))
                }
            }
        }
        if (label != null) {
            Text(
                text = label,
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Mint into blue, the colours of TV Slim. */
@Composable
private fun ringBrush(): Brush = Brush.sweepGradient(
    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.primary),
)

/** Drawn over the keys, so a pressed one does not cut it. */
@Composable
private fun Ring(modifier: Modifier) {
    Box(modifier = modifier.border(RING_WIDTH, ringBrush(), CircleShape))
}

@Composable
private fun padBackground(): Brush = Brush.radialGradient(
    listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainer),
)

/** The arrows, and the touchpad beside them: a swipe goes from one to the other, and so do the dots. */
@Composable
private fun PadPager(side: Dp, actions: RemoteControlActions, haptic: HapticFeedback, modifier: Modifier) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(side)) { page ->
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (page == 0) DirectionPad(side, actions, haptic) else Touchpad(side, actions, haptic)
            }
        }
        Row(modifier = Modifier.height(DOTS_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
            for ((page, label) in listOf(R.string.remote_pad, R.string.remote_touchpad).withIndex()) {
                val current = pager.currentPage == page
                Box(
                    modifier = Modifier
                        .size(DOTS_HEIGHT)
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(label)) { scope.launch { pager.animateScrollToPage(page) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
        }
    }
}

/**
 * The TV's mouse: one finger moves the pointer, a tap clicks, two fingers scroll. Its drags are its own, not the
 * pager's: going back to the arrows is a swipe beside it, or the dots.
 */
@Composable
private fun Touchpad(side: Dp, actions: RemoteControlActions, haptic: HapticFeedback) {
    val current by rememberUpdatedState(actions)
    val shape = RoundedCornerShape(28.dp)
    val label = stringResource(R.string.remote_touchpad)
    Box(
        modifier = Modifier
            .size(side * TOUCHPAD_WIDTH, side)
            .clip(shape)
            .background(padBackground())
            .border(RING_WIDTH, ringBrush(), shape)
            .semantics {
                contentDescription = label
                onClick {
                    current.onPointerClick()
                    true
                }
            }
            .pointerInput(Unit) { touchpadGestures({ current }, haptic) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Mouse, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = stringResource(R.string.remote_touchpad_hint),
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Moves wait for the touch slop, so a tap that wobbles still clicks; fractions of a count carry to the next move. */
private suspend fun PointerInputScope.touchpadGestures(actions: () -> RemoteControlActions, haptic: HapticFeedback) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        var travelled = 0f
        var fingers = 1
        var restX = 0f
        var restY = 0f
        var restWheel = 0f
        while (true) {
            val event = awaitPointerEvent()
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break
            fingers = maxOf(fingers, down.size)
            val moved = down.fold(Offset.Zero) { sum, change -> sum + change.positionChange() } / down.size.toFloat()
            event.changes.forEach { it.consume() }
            travelled += moved.getDistance()
            if (travelled < viewConfiguration.touchSlop) continue
            if (down.size > 1) {
                // Content follows the fingers, as on the phone: down is a notch up.
                restWheel += moved.y.toDp() / WHEEL_STEP
                val notches = restWheel.toInt()
                restWheel -= notches
                if (notches != 0) actions().onPointer(0, 0, notches)
            } else {
                restX += moved.x.toDp().value * POINTER_SPEED
                restY += moved.y.toDp().value * POINTER_SPEED
                val dx = restX.toInt()
                val dy = restY.toInt()
                restX -= dx
                restY -= dy
                if (dx != 0 || dy != 0) actions().onPointer(dx, dy, 0)
            }
        }
        if (travelled < viewConfiguration.touchSlop && fingers == 1 && actions().onPointerClick()) {
            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        }
    }
}

/**
 * The phone's own dictation, typed into the field focused on the TV: none of the test TVs has a voice search left to
 * open, and ADB carries no sound. The dictation may stop the remote's link for a moment; what was said waits for it.
 */
@Composable
private fun VoiceButton(scale: Float, ready: Boolean, actions: RemoteControlActions, haptic: HapticFeedback) {
    val context = LocalContext.current
    val current by rememberUpdatedState(actions)
    val prompt = stringResource(R.string.remote_voice_prompt)
    var said by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) said = text to SystemClock.elapsedRealtime()
    }
    LaunchedEffect(said, ready) {
        val (text, at) = said ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        said = null
        if (SystemClock.elapsedRealtime() - at > VOICE_WAIT_MS) return@LaunchedEffect
        haptic.performHapticFeedback(if (current.onText(text)) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    }
    SheetButton(R.string.remote_voice, Icons.Filled.Mic, scale) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            dictation.launch(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(context, R.string.remote_voice_unavailable, Toast.LENGTH_LONG).show()
        }
    }
}

/** Three by three cells inside a disc: each arrow is its own button, large enough for a thumb. */
@Composable
private fun DirectionPad(side: Dp, actions: RemoteControlActions, haptic: HapticFeedback) {
    Box(
        modifier = Modifier
            .size(side)
            .clip(CircleShape)
            .background(padBackground()),
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
                        label = stringResource(R.string.remote_ok),
                        actions = actions,
                        haptic = haptic,
                        modifier = Modifier.size(cell - 8.dp),
                        shape = CircleShape,
                        idle = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = stringResource(R.string.remote_ok),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Ring(Modifier.size(cell - 8.dp))
                }
                Arrow(RemoteKey.RIGHT, R.string.remote_right, 90f, cell, actions, haptic)
            }
            Row {
                Spacer(Modifier.size(cell))
                Arrow(RemoteKey.DOWN, R.string.remote_down, 180f, cell, actions, haptic)
                Spacer(Modifier.size(cell))
            }
        }
        Ring(Modifier.matchParentSize())
    }
}

/** Sizes of the keys block, which fills the height under the header rather than leaving it empty. */
private data class KeyMetrics(val keyScale: Float, val gapScale: Float, val pad: Dp) {
    fun gap(base: Dp): Dp = base * gapScale
}

/**
 * Everything grows or shrinks together, so a short phone keeps all its keys. On a tall one the pad stops at the
 * width and the keys at a thumb's size, or at five abreast; the gaps take the rest, then the room above.
 */
private fun keyMetrics(width: Dp, height: Dp, withShortcuts: Boolean, withDots: Boolean): KeyMetrics {
    val gaps = MEDIA_GAP + PAD_GAP + ROW_GAP + if (withShortcuts) SHORTCUTS_GAP else 0.dp
    val room = height - (if (withShortcuts) SHORTCUT_SIZE else 0.dp) - (if (withDots) DOTS_HEIGHT else 0.dp)
    val keys = KEY_SIZE * 3
    val scale = (room / (keys + gaps + PAD_SIZE)).coerceIn(MIN_SCALE, MAX_SCALE)
    val pad = min(PAD_SIZE * scale, width * PAD_WIDTH)
    val keyScale = minOf(scale, MAX_KEY_SCALE, width / BLOCK_WIDTH)
    val gapScale = ((room - pad - keys * keyScale) / gaps).coerceIn(MIN_SCALE, MAX_GAP_SCALE)
    return KeyMetrics(keyScale, gapScale, pad)
}

/** Sideways: the pad as tall as the room, and the keys as large as the columns beside it allow. */
private fun sidewaysKeyMetrics(width: Dp, height: Dp, withShortcuts: Boolean, withDots: Boolean): KeyMetrics {
    val pad = min(height - if (withDots) DOTS_HEIGHT else 0.dp, width * SIDEWAYS_PAD_WIDTH)
    val column = (width - pad - PAD_GAP * 2) / 2
    val scale = minOf(
        height / (KEY_SIZE * 2 + ROW_GAP),
        (height - if (withShortcuts) SHORTCUT_SIZE + SHORTCUTS_GAP else 0.dp) / KEY_SIZE,
        column / BLOCK_WIDTH,
    ).coerceIn(MIN_SCALE, MAX_KEY_SCALE)
    return KeyMetrics(scale, scale, pad)
}

private val KEY_SIZE = 60.dp
private val KEY_ICON_SIZE = 24.dp
private val PAD_SIZE = 300.dp
private const val PAD_WIDTH = 0.9f
private const val SIDEWAYS_PAD_WIDTH = 0.4f
private val RING_WIDTH = 2.dp
private const val TOUCHPAD_WIDTH = 0.85f
private val DOTS_HEIGHT = 24.dp
private val MEDIA_GAP = 16.dp
private val PAD_GAP = 24.dp
private val ROW_GAP = 12.dp
private val SHORTCUTS_GAP = 16.dp

/** A pill, three keys and a pill abreast, with the least room a thumb needs between them. */
private val BLOCK_WIDTH = KEY_SIZE * 5 + 12.dp * 4
private const val MIN_SCALE = 0.5f
private const val MAX_SCALE = 1.3f
private const val MAX_KEY_SCALE = 1.15f
private const val MAX_GAP_SCALE = 2f

/** StartLight's notice on two lines, with the space above it. */
private val NOTICE_ROOM = 68.dp

/** Mouse counts per dp the finger slides; the TV speeds fast moves up by itself. */
private const val POINTER_SPEED = 1.5f

/** Two fingers sliding this far scroll by one notch. */
private val WHEEL_STEP = 20.dp

/** Longer than the link takes to come back after the dictation; later, the text could land in another field. */
private const val VOICE_WAIT_MS = 5_000L

/** The number pad has the sheet to itself: its keys can be larger than the remote's. */
private const val DIGIT_SCALE = 1.2f

/** One glyph rotated rather than the left and right arrows, which mirror in right-to-left languages. */
@Composable
private fun Arrow(key: RemoteKey, description: Int, rotation: Float, cell: Dp, actions: RemoteControlActions, haptic: HapticFeedback) {
    KeySurface(
        key = key,
        label = stringResource(description),
        actions = actions,
        haptic = haptic,
        modifier = Modifier.size(cell),
        shape = RoundedCornerShape(cell / 3),
        idle = Color.Transparent,
    ) {
        Icon(
            Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            modifier = Modifier.size(cell * 0.4f).rotate(rotation),
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

/**
 * Laid out as a phone's dial pad. It stays open, since a channel number takes several digits; the TV goes to the
 * channel after its own delay, or at once on OK.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigitsSheet(actions: RemoteControlActions, haptic: HapticFeedback, onDismiss: () -> Unit) {
    // Taller than half the screen, it would otherwise open half way with its last row under the edge.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.remote_digits), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.remote_digits_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (row in (1..9).chunked(3)) {
                KeyRow { for (digit in row) DigitKey(digit, actions, haptic) }
            }
            KeyRow {
                RoundKey(RemoteKey.LAST_CHANNEL, R.string.remote_last_channel, Icons.Filled.History, actions, haptic, DIGIT_SCALE)
                DigitKey(0, actions, haptic)
                RoundKey(RemoteKey.CENTER, R.string.remote_ok, null, actions, haptic, DIGIT_SCALE) {
                    Text(
                        text = stringResource(R.string.remote_ok),
                        modifier = Modifier.clearAndSetSemantics {},
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Int, actions: RemoteControlActions, haptic: HapticFeedback) {
    val text = digit.toString()
    KeySurface(
        key = RemoteKey.digits[digit],
        label = text,
        actions = actions,
        haptic = haptic,
        modifier = Modifier.size(KEY_SIZE * DIGIT_SCALE),
        shape = CircleShape,
        idle = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(text, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineSmall)
    }
}
