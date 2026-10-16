package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
 * StartLight's own buttons, when it is the TV's home. Only what its state announces is shown: a button that opens
 * nothing is worse than none. Every refusal is said in words.
 */
@Composable
internal fun StartLightSection(ui: StartLightUi, actions: StartLightActions) {
    val haptic = LocalHapticFeedback.current
    val send = { command: StartLightCommand ->
        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
        actions.onCommand(command)
    }
    var pages by rememberSaveable { mutableStateOf(false) }
    val state = ui.state

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val behind = state?.behindAnotherApp == true
            val asking = state?.permissionRequest == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LogoLauncher(StartLightHome.LAUNCHER_ID, size = 36.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.startlight_name), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(
                            when {
                                state == null -> R.string.remote_startlight_home
                                asking -> R.string.startlight_permission_request
                                behind -> R.string.startlight_not_foreground
                                else -> screenText(state)
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (state?.pro == true) {
                    Text(
                        text = stringResource(R.string.startlight_pro),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Right under the header, where it is always in sight; what the header already says is not repeated.
            ui.notice?.takeUnless {
                it is StartLightNotice.Refused &&
                    (behind && it.refusal == StartLightRefusal.NOT_FOREGROUND || asking && it.refusal == StartLightRefusal.PERMISSION_REQUEST)
            }
                ?.let { notice ->
                    Text(
                        text = noticeText(notice),
                        style = MaterialTheme.typography.bodySmall,
                        // The TV answered as its own button would (a code to type, the Pro screen): not a failure.
                        color = if (notice is StartLightNotice.Refused && notice.refusal in INFORMATIVE) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

            when {
                ui.absence == StartLightAbsence.NO_DOOR -> Hint(stringResource(R.string.startlight_no_door))
                ui.absence == StartLightAbsence.NOT_STARTED -> {
                    Hint(stringResource(R.string.startlight_not_started))
                    BringHomeButton(ui, actions)
                }
                state == null -> Unit
                !state.ready -> Hint(stringResource(R.string.startlight_not_ready))
                // Its own permission request: answered on the TV, the header says so; HOME would close it unanswered.
                asking -> Unit
                // Its buttons would all be refused until it is in front again.
                behind -> BringHomeButton(ui, actions)

                else -> {
                    if (state.offersLeaveRest) {
                        Button(
                            onClick = { send(StartLightCommand.LeaveRest) },
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.startlight_leave_rest)) }
                    }
                    val withPages = state.offeredPages.isNotEmpty() || state.offeredDashboards.isNotEmpty()
                    if (withPages || state.actions.isNotEmpty()) {
                        // One row swiped sideways: twelve buttons on several lines would push the keys off the thumb.
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (withPages) {
                                AssistChip(
                                    onClick = { pages = true },
                                    enabled = !ui.busy,
                                    label = { Text(stringResource(pagesLabel(state))) },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Dashboard, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                                    },
                                )
                            }
                            state.actions.forEach { action ->
                                AssistChip(
                                    onClick = { send(StartLightCommand.Action(action)) },
                                    enabled = !ui.busy,
                                    label = {
                                        Text(
                                            if (state.lockedByPro(action)) {
                                                stringResource(actionLabel(action)) + " · " + stringResource(R.string.startlight_pro)
                                            } else {
                                                stringResource(actionLabel(action))
                                            },
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(actionIcon(action), contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pages && state != null) {
        PagesSheet(state, onCommand = { command ->
            pages = false
            send(command)
        }, onDismiss = { pages = false })
    }
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

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PagesSheet(state: StartLightState, onCommand: (StartLightCommand) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            if (state.offeredPages.isNotEmpty()) {
                SheetTitle(stringResource(R.string.startlight_pages_title))
                state.offeredPages.forEach { page ->
                    SheetLine(page.title.ifBlank { page.id }, if (page.home) Icons.Filled.Home else null) {
                        onCommand(StartLightCommand.OpenPage(page))
                    }
                }
            }
            if (state.offeredDashboards.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SheetTitle(stringResource(R.string.startlight_dashboards_title))
                state.offeredDashboards.forEach { dashboard ->
                    SheetLine(dashboard.title.ifBlank { dashboard.id }, Icons.Filled.Dashboard) {
                        onCommand(StartLightCommand.OpenDashboard(dashboard))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun SheetLine(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider()
}

private val INFORMATIVE = setOf(
    StartLightRefusal.PARENTAL_CODE,
    StartLightRefusal.PRO_REQUIRED,
    StartLightRefusal.NO_EFFECT,
    StartLightRefusal.PERMISSION_REQUEST,
)

/** Names what the sheet holds: the menu's pages, the dashboards, or both. */
private fun pagesLabel(state: StartLightState): Int = when {
    state.offeredDashboards.isEmpty() -> R.string.startlight_pages_title
    state.offeredPages.isEmpty() -> R.string.startlight_dashboards_title
    else -> R.string.startlight_pages
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

private fun actionIcon(action: StartLightAction): ImageVector = when (action) {
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
