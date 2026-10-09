package net.jolabs40.tvslim.windows.ui.screens

import net.jolabs40.tvslim.windows.resources.reboot_in_progress
import net.jolabs40.tvslim.windows.resources.device_reboot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import net.jolabs40.tvslim.device.Manufacturer
import net.jolabs40.tvslim.device.DeviceType
import net.jolabs40.tvslim.windows.adb.ConnectionUi
import net.jolabs40.tvslim.windows.adb.ConnectionState
import net.jolabs40.tvslim.windows.adb.ConnectionProblem
import net.jolabs40.tvslim.windows.network.DiscoveredDevice
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_connected_tv_24
import net.jolabs40.tvslim.windows.resources.baseline_wifi_find_24
import net.jolabs40.tvslim.windows.resources.connection_connect
import net.jolabs40.tvslim.windows.resources.connection_help_1
import net.jolabs40.tvslim.windows.resources.connection_help_2
import net.jolabs40.tvslim.windows.resources.connection_help_3
import net.jolabs40.tvslim.windows.resources.connection_help_4
import net.jolabs40.tvslim.windows.resources.connection_help_title
import net.jolabs40.tvslim.windows.resources.connection_host
import net.jolabs40.tvslim.windows.resources.connection_host_hint
import net.jolabs40.tvslim.windows.resources.connection_manual_title
import net.jolabs40.tvslim.windows.resources.connection_port
import net.jolabs40.tvslim.windows.resources.connection_problem_other
import net.jolabs40.tvslim.windows.resources.connection_problem_refused
import net.jolabs40.tvslim.windows.resources.connection_problem_timeout
import net.jolabs40.tvslim.windows.resources.connection_problem_unauthorized
import net.jolabs40.tvslim.windows.resources.connection_problem_unreachable
import net.jolabs40.tvslim.windows.resources.connection_subtitle
import net.jolabs40.tvslim.windows.resources.connection_title
import net.jolabs40.tvslim.windows.resources.connection_waiting
import net.jolabs40.tvslim.windows.resources.discovery_hint
import net.jolabs40.tvslim.windows.resources.discovery_none
import net.jolabs40.tvslim.windows.resources.discovery_searching
import net.jolabs40.tvslim.windows.resources.discovery_title
import net.jolabs40.tvslim.windows.resources.discovery_wireless
import net.jolabs40.tvslim.windows.ui.TvAppActions
import net.jolabs40.tvslim.windows.ui.CommandActions
import net.jolabs40.tvslim.windows.ui.PermissionsActions
import net.jolabs40.tvslim.windows.ui.AppState
import net.jolabs40.tvslim.windows.ui.ApplicationsState
import net.jolabs40.tvslim.windows.ui.TvAppUiState
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.TwoColumns
import net.jolabs40.tvslim.windows.ui.components.BrandPlate
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * TV tab: find the device and connect, then its status, home screen, privileged permissions and APK install.
 * The companion's content, in two columns.
 *
 * No QR scanner: nothing requires the app on the TV, so discovery and typing the address are enough.
 */
@Composable
fun ConnectionScreen(
    state: AppState,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onInstallLauncher: (String) -> Unit,
    onSetHome: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onRevertDrift: () -> Unit,
    onSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onConnectTo: (DiscoveredDevice) -> Unit,
    permissionsActions: PermissionsActions,
    applicationsState: ApplicationsState,
    tvAppState: TvAppUiState,
    tvAppActions: TvAppActions,
    onChooseApk: () -> Unit,
    commandActions: CommandActions,
    onReboot: () -> Unit = {},
) {
    if (state.connected) {
        TwoColumns(
            left = {
                // Unlike the phone, which puts the action first to avoid scrolling, everything fits here:
                // device first, then its home screen. Drift comes before anything else.
                state.drift?.let { plan -> DriftCard(plan = plan, onRevert = onRevertDrift) }
                // TV or box only; pointless on a phone connected for testing.
                val isTv = state.info.deviceType == DeviceType.TV || state.info.deviceType == DeviceType.BOX
                DeviceCard(
                    state = state,
                    onDisconnect = onDisconnect,
                    onReboot = onReboot,
                    // Refresh also re-reads the TV app, which may have changed on the TV.
                    onRefresh = { onRefresh(); if (isTv) tvAppActions.onRead() },
                )
                if (isTv) {
                    TvAppCard(state = tvAppState, host = state.connection.host, actions = tvAppActions)
                }
                HomeCard(
                    state = state,
                    onInstall = onInstallLauncher,
                    onSetHome = onSetHome,
                    onOpenLink = onOpenLink,
                )
            },
            right = {
                PermissionsCard(state = state.permissions, applications = applicationsState, actions = permissionsActions)
                InstallationCard(state = state.installation, onChoose = onChooseApk)
                CommandCard(state = state.command, actions = commandActions)
            },
        )
        return
    }

    // Discovery only runs while this screen is visible: it stops when the window is minimized and
    // resumes when it is restored.
    LifecycleStartEffect(Unit) {
        onSearch()
        onStopOrDispose { onStopSearch() }
    }

    TwoColumns(
        left = {
            Header()
            // Requested reboot: say we are waiting for the TV to come back.
            if (state.rebooting) {
                SectionCard(title = stringResource(Res.string.device_reboot)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(text = stringResource(Res.string.reboot_in_progress), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            DiscoveryCard(state = state, onConnectTo = onConnectTo)
            InputCard(state = state, onHost = onHost, onPort = onPort, onConnect = onConnect)
        },
        right = {
            HelpCard()
        },
    )
}

@Composable
private fun ColumnScope.Header() {
    Text(
        text = stringResource(Res.string.connection_title),
        style = MaterialTheme.typography.titleLarge,
    )
    SecondaryText(stringResource(Res.string.connection_subtitle))
}

@Composable
private fun DiscoveryCard(state: AppState, onConnectTo: (DiscoveredDevice) -> Unit) {
    val devices = state.detected
    val inProgress = state.connection.state == ConnectionState.CONNECTION

    SectionCard(title = stringResource(Res.string.discovery_title)) {
        when {
            devices.isEmpty() && !state.discovery.firstRoundDone -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                SecondaryText(stringResource(Res.string.discovery_searching))
            }

            devices.isEmpty() -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.baseline_wifi_find_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SecondaryText(stringResource(Res.string.discovery_none))
            }

            else -> {
                SecondaryText(stringResource(Res.string.discovery_hint), small = true)
                devices.forEach { device ->
                    DeviceButton(
                        device = device,
                        // Brand of a device seen before: its remembered name starts with it.
                        manufacturer = state.knownNames[device.host]?.let { Manufacturer.fromName(it) },
                        active = !inProgress,
                        onClick = { onConnectTo(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceButton(device: DiscoveredDevice, manufacturer: Manufacturer?, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = active,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (manufacturer != null) {
            BrandPlate(manufacturer = manufacturer, height = 30.dp)
        } else {
            Icon(painter = painterResource(Res.drawable.baseline_connected_tv_24), contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Without a known name the address alone is shown, not twice.
            val address = "${device.host}:${device.port}"
            Text(
                text = if (device.friendlyName != null) device.label else address,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (device.friendlyName != null) {
                Text(text = address, style = MaterialTheme.typography.bodySmall)
            }
            if (device.wireless) {
                Text(
                    text = stringResource(Res.string.discovery_wireless),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InputCard(
    state: AppState,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val inProgress = state.connection.state == ConnectionState.CONNECTION

    SectionCard(title = stringResource(Res.string.connection_manual_title), spacing = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.enteredHost,
                onValueChange = onHost,
                label = { Text(stringResource(Res.string.connection_host)) },
                placeholder = { Text(stringResource(Res.string.connection_host_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f).onEnter(onConnect),
            )
            OutlinedTextField(
                value = state.enteredPort,
                onValueChange = onPort,
                label = { Text(stringResource(Res.string.connection_port)) },
                singleLine = true,
                modifier = Modifier.width(110.dp).onEnter(onConnect),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onConnect, enabled = !inProgress) {
                Text(stringResource(Res.string.connection_connect))
            }
            if (inProgress) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        }
        if (inProgress) SecondaryText(stringResource(Res.string.connection_waiting))
        ErrorState(state.connection)
    }
}

/** Why the connection failed in plain words, then the technical message. */
@Composable
private fun ErrorState(connection: ConnectionUi) {
    if (connection.state != ConnectionState.ERROR) return
    val explanation = when (connection.problem ?: ConnectionProblem.OTHER) {
        ConnectionProblem.REJECTED -> Res.string.connection_problem_refused
        ConnectionProblem.TIMEOUT -> Res.string.connection_problem_timeout
        ConnectionProblem.UNAUTHORIZED -> Res.string.connection_problem_unauthorized
        ConnectionProblem.UNREACHABLE -> Res.string.connection_problem_unreachable
        ConnectionProblem.OTHER -> Res.string.connection_problem_other
    }
    Text(
        text = stringResource(explanation),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (connection.detail.isNotBlank()) {
        SelectionContainer { SecondaryText(connection.detail, small = true) }
    }
}

@Composable
private fun HelpCard() {
    SectionCard(title = stringResource(Res.string.connection_help_title), spacing = 12.dp) {
        listOf(
            Res.string.connection_help_1,
            Res.string.connection_help_2,
            Res.string.connection_help_3,
            Res.string.connection_help_4,
        ).forEach { step ->
            Text(text = stringResource(step), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Enter in a field acts as a click on "Connect". */
private fun Modifier.onEnter(action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val entry = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (entry && event.type == KeyEventType.KeyDown) {
        action()
        true
    } else {
        false
    }
}
