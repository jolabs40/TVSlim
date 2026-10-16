package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.jolabs40.tvslim.control.CallFailure
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.StartLightNotice
import net.jolabs40.tvslim.remote.ui.StartLightUi
import net.jolabs40.tvslim.startlight.StartLightAbsence
import net.jolabs40.tvslim.startlight.StartLightAction
import net.jolabs40.tvslim.startlight.StartLightCommand
import net.jolabs40.tvslim.startlight.StartLightHome
import net.jolabs40.tvslim.startlight.StartLightRefusal
import net.jolabs40.tvslim.startlight.StartLightState

class StartLightActions(
    val onCommand: (StartLightCommand) -> Unit,
    val onBringHome: () -> Unit,
)

/**
 * Above the remote's keys: StartLight, what it is showing, and the way to its own buttons. The keys keep their
 * room; the buttons live on their own page.
 */
@Composable
internal fun StartLightBanner(ui: StartLightUi, onOpen: () -> Unit) {
    // The answer to a shortcut pressed on the remote, which does not open the page: in place of the status for a
    // while, so the banner keeps its height above the keys.
    val notice = shownNotice(ui)
    var noticeShown by remember(notice) { mutableStateOf(notice != null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(BANNER_NOTICE_MS)
            noticeShown = false
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.startlight_open), role = Role.Button, onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LogoLauncher(StartLightHome.LAUNCHER_ID, size = 36.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.startlight_name), style = MaterialTheme.typography.titleSmall)
                if (notice != null && noticeShown) {
                    NoticeText(notice, MaterialTheme.typography.bodySmall, maxLines = 2)
                } else {
                    Text(
                        text = stringResource(status(ui)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ui.busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(R.string.startlight_open),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * StartLight's own buttons, on a page of their own. Only what its state announces is shown: a button that opens
 * nothing is worse than none. Every refusal is said in words, under the title where it stays in sight.
 */
@Composable
internal fun StartLightPage(ui: StartLightUi, actions: StartLightActions, onClose: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val send = { command: StartLightCommand ->
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        actions.onCommand(command)
    }
    val state = ui.state
    val behind = state?.behindAnotherApp == true
    val asking = state?.permissionRequest == true

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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.startlight_back_to_remote))
            }
            LogoLauncher(StartLightHome.LAUNCHER_ID, size = 32.dp)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.startlight_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    text = stringResource(status(ui)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (state?.pro == true) {
                Text(
                    text = stringResource(R.string.startlight_pro),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        shownNotice(ui)?.let { NoticeText(it, MaterialTheme.typography.bodyMedium) }

        when {
            ui.absence == StartLightAbsence.NO_DOOR -> Hint(stringResource(R.string.startlight_no_door))
            ui.absence == StartLightAbsence.NOT_STARTED -> {
                Hint(stringResource(R.string.startlight_not_started))
                BringHomeButton(ui, actions)
            }

            state == null -> Unit
            !state.ready -> Hint(stringResource(R.string.startlight_not_ready))
            // Its own permission request: answered on the TV, the status line says so; HOME would close it unanswered.
            asking -> Unit
            // Its buttons would all be refused until it is in front again.
            behind -> BringHomeButton(ui, actions)
            else -> Buttons(state, ui.busy, send)
        }
    }
}

@Composable
private fun Buttons(state: StartLightState, busy: Boolean, send: (StartLightCommand) -> Unit) {
    if (state.offersLeaveRest) {
        Button(
            onClick = { send(StartLightCommand.LeaveRest) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.startlight_leave_rest)) }
    }

    if (state.actions.isNotEmpty()) {
        SectionTitle(stringResource(R.string.startlight_actions_title))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.actions.chunked(COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { action ->
                        ActionTile(
                            action = action,
                            locked = state.lockedByPro(action),
                            enabled = !busy,
                            onClick = { send(StartLightCommand.Action(action)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    if (state.offeredPages.isNotEmpty()) {
        SectionTitle(stringResource(R.string.startlight_pages_title))
        Column {
            state.offeredPages.forEach { page ->
                Line(page.title.ifBlank { page.id }, if (page.home) Icons.Filled.Home else Icons.AutoMirrored.Filled.ViewQuilt, !busy) {
                    send(StartLightCommand.OpenPage(page))
                }
            }
        }
    }

    if (state.offeredDashboards.isNotEmpty()) {
        SectionTitle(stringResource(R.string.startlight_dashboards_title))
        Column {
            state.offeredDashboards.forEach { dashboard ->
                Line(dashboard.title.ifBlank { dashboard.id }, Icons.Filled.Dashboard, !busy) {
                    send(StartLightCommand.OpenDashboard(dashboard))
                }
            }
        }
    }
}

/** A large tile: the page is there to be read and pressed at a glance, one thumb on the phone. */
@Composable
private fun ActionTile(action: StartLightAction, locked: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(92.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(actionIcon(action), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(actionLabel(action)),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (locked) {
                Text(
                    text = stringResource(R.string.startlight_pro),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Line(text: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
    HorizontalDivider()
}

/** HOME brings StartLight in front, and starts it where the TV will not start it for a call. */
@Composable
private fun BringHomeButton(ui: StartLightUi, actions: StartLightActions) {
    FilledTonalButton(onClick = actions.onBringHome, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.startlight_bring_home))
    }
}

/** The last command's answer, unless the status line already says it. */
private fun shownNotice(ui: StartLightUi): StartLightNotice? {
    val behind = ui.state?.behindAnotherApp == true
    val asking = ui.state?.permissionRequest == true
    return ui.notice?.takeUnless {
        it is StartLightNotice.Refused &&
            (behind && it.refusal == StartLightRefusal.NOT_FOREGROUND || asking && it.refusal == StartLightRefusal.PERMISSION_REQUEST)
    }
}

@Composable
private fun NoticeText(notice: StartLightNotice, style: TextStyle, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text = noticeText(notice),
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        // The TV answered as its own button would (a code to type, the Pro screen): not a failure.
        color = if (notice is StartLightNotice.Refused && notice.refusal in INFORMATIVE) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val COLUMNS = 3

private const val BANNER_NOTICE_MS = 8_000L

private val INFORMATIVE = setOf(
    StartLightRefusal.PARENTAL_CODE,
    StartLightRefusal.PRO_REQUIRED,
    StartLightRefusal.NO_EFFECT,
    StartLightRefusal.PERMISSION_REQUEST,
)

/** One line for the banner and the page's title: what StartLight shows, or why it cannot be driven. */
private fun status(ui: StartLightUi): Int {
    val state = ui.state
    return when {
        ui.absence == StartLightAbsence.NOT_STARTED -> R.string.startlight_screen_not_started
        ui.absence == StartLightAbsence.NO_DOOR -> R.string.startlight_screen_no_door
        state == null -> R.string.remote_startlight_home
        state.permissionRequest -> R.string.startlight_permission_request
        state.behindAnotherApp -> R.string.startlight_not_foreground
        else -> screenText(state)
    }
}

private fun screenText(state: StartLightState): Int = when (state.screen) {
    "repos" -> R.string.startlight_screen_rest
    "reglages" -> R.string.startlight_screen_settings
    "calque" -> R.string.startlight_screen_layer
    "code_parental" -> R.string.startlight_screen_parental
    "pro" -> R.string.startlight_screen_pro
    "indisponible" -> R.string.startlight_screen_starting
    else -> R.string.startlight_screen_home
}

internal fun actionLabel(action: StartLightAction): Int = when (action) {
    StartLightAction.SETTINGS -> R.string.startlight_action_settings
    StartLightAction.NOTIFICATIONS -> R.string.startlight_action_notifications
    StartLightAction.NETWORK -> R.string.startlight_action_network
    StartLightAction.NEXT_WALLPAPER -> R.string.startlight_action_next_wallpaper
    StartLightAction.REST -> R.string.startlight_action_rest
    StartLightAction.SEARCH -> R.string.startlight_action_search
    StartLightAction.RECENTS -> R.string.startlight_action_recents
    StartLightAction.PLANNING -> R.string.startlight_action_planning
    StartLightAction.MEDIA -> R.string.startlight_action_media
    StartLightAction.MENU_LAYOUT -> R.string.startlight_action_menu_layout
    StartLightAction.HELP -> R.string.startlight_action_help
    StartLightAction.CONNECTORS -> R.string.startlight_action_connectors
}

internal fun actionIcon(action: StartLightAction): ImageVector = when (action) {
    StartLightAction.SETTINGS -> Icons.Filled.Settings
    StartLightAction.NOTIFICATIONS -> Icons.Filled.Notifications
    StartLightAction.NETWORK -> Icons.Filled.Wifi
    StartLightAction.NEXT_WALLPAPER -> Icons.Filled.Wallpaper
    StartLightAction.REST -> Icons.Filled.Bedtime
    StartLightAction.SEARCH -> Icons.Filled.Search
    StartLightAction.RECENTS -> Icons.Filled.Restore
    StartLightAction.PLANNING -> Icons.Filled.CalendarMonth
    StartLightAction.MEDIA -> Icons.Filled.PlayCircle
    StartLightAction.MENU_LAYOUT -> Icons.Filled.SwapHoriz
    StartLightAction.HELP -> Icons.AutoMirrored.Filled.Help
    StartLightAction.CONNECTORS -> Icons.Filled.QrCode2
}

/** Each reason of the contract has its own sentence; one this version does not know is quoted. */
internal fun noticeResource(notice: StartLightNotice): Int = when (notice) {
    is StartLightNotice.Refused -> when (notice.refusal) {
        StartLightRefusal.UNKNOWN -> R.string.startlight_refused_unknown
        StartLightRefusal.HOME_NOT_READY -> R.string.startlight_refused_not_ready
        StartLightRefusal.NOT_FOREGROUND -> R.string.startlight_refused_not_foreground
        StartLightRefusal.BUSY -> R.string.startlight_refused_busy
        StartLightRefusal.PARENTAL_CODE -> R.string.startlight_refused_parental
        StartLightRefusal.PRO_REQUIRED -> R.string.startlight_refused_pro
        StartLightRefusal.NO_EFFECT -> R.string.startlight_refused_no_effect
        StartLightRefusal.PERMISSION_REQUEST -> R.string.startlight_refused_permission
        StartLightRefusal.OTHER -> R.string.startlight_refused_other
    }

    is StartLightNotice.NotDelivered -> if (notice.failure == CallFailure.TIMEOUT) R.string.startlight_no_answer else R.string.startlight_not_delivered
    is StartLightNotice.Failed -> R.string.startlight_failed
}

@Composable
private fun noticeText(notice: StartLightNotice): String = when (notice) {
    is StartLightNotice.Refused -> stringResource(noticeResource(notice), notice.reason)
    is StartLightNotice.Failed -> stringResource(noticeResource(notice), notice.detail)
    is StartLightNotice.NotDelivered -> stringResource(noticeResource(notice))
}
