package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import net.jolabs40.tvslim.device.Manufacturer
import net.jolabs40.tvslim.device.DeviceType
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.adb.ConnectionUi
import net.jolabs40.tvslim.remote.adb.ConnectionState
import net.jolabs40.tvslim.remote.adb.ConnectionProblem
import net.jolabs40.tvslim.remote.ui.CommandActions
import net.jolabs40.tvslim.remote.ui.PermissionsActions
import net.jolabs40.tvslim.remote.ui.ShizukuActions
import net.jolabs40.tvslim.remote.ui.RemoteState

/** The scanner UI module is not in the APK: Play services downloads it. */
private enum class ModuleState { UNKNOWN, DOWNLOADING, READY, UNAVAILABLE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: RemoteState,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onReboot: () -> Unit,
    onScan: (String) -> Unit,
    onScanFailure: (String) -> Unit,
    onInstallLauncher: (String) -> Unit,
    onSetHome: (String) -> Unit,
    onRevertDrift: () -> Unit,
    onSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onConnectTo: (net.jolabs40.tvslim.remote.adb.DiscoveredDevice) -> Unit,
    permissionsActions: PermissionsActions,
    applicationsState: net.jolabs40.tvslim.remote.ui.ApplicationsState,
    tvAppState: net.jolabs40.tvslim.remote.ui.TvAppUiState,
    tvAppActions: net.jolabs40.tvslim.remote.ui.TvAppActions,
    onChooseApk: () -> Unit,
    commandActions: CommandActions,
    actionsShizuku: ShizukuActions,
) {
    val context = LocalContext.current
    val options = remember {
        GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    }
    val scanner = remember { GmsBarcodeScanning.getClient(context, options) }
    var moduleState by remember { mutableStateOf(ModuleState.UNKNOWN) }

    // Requested when the screen opens, not on first tap: otherwise the first scan waits for the download, stuck on
    // "Waiting for the Barcode UI module to be downloaded".
    LaunchedEffect(Unit) {
        val installer = ModuleInstall.getClient(context)
        installer.areModulesAvailable(scanner)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    moduleState = ModuleState.READY
                } else {
                    moduleState = ModuleState.DOWNLOADING
                    installer
                        .installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                        .addOnSuccessListener { moduleState = ModuleState.READY }
                        .addOnFailureListener { moduleState = ModuleState.UNAVAILABLE }
                }
            }
            .addOnFailureListener { moduleState = ModuleState.UNAVAILABLE }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.connection_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.connection_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.connected) {
            // Actions before measurements, so the useful button needs no scrolling. Drift comes first of all.
            state.drift?.let { plan -> DriftCard(plan = plan, onRevert = onRevertDrift) }
            HomeCard(state = state, onInstall = onInstallLauncher, onSetHome = onSetHome)
            // TV or box only: the TV app has no use on a phone connected for testing.
            val isTv = state.info.deviceType == DeviceType.TV || state.info.deviceType == DeviceType.BOX
            ConnectedDevice(
                state = state,
                onDisconnect = onDisconnect,
                onReboot = onReboot,
                // Also re-reads the TV app, which may have changed on the TV.
                onRefresh = { onRefresh(); if (isTv) tvAppActions.onRead() },
            )
            if (isTv) {
                TvAppCard(state = tvAppState, host = state.connection.host, actions = tvAppActions)
            }
            // Last and collapsed: rarely used tools unrelated to debloating.
            AdvancedTools(
                busy = state.permissions.reading || state.installation.busy ||
                    state.command.inProgress || state.shizuku.inProgress,
            ) {
                PermissionsCard(state = state.permissions, applications = applicationsState, actions = permissionsActions)
                InstallationCard(state = state.installation, onChoose = onChooseApk)
                CommandCard(state = state.command, actions = commandActions)
                ShizukuCard(state = state.shizuku, actions = actionsShizuku)
            }
            return@Column
        }

        if (state.rebooting) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(text = stringResource(R.string.reboot_in_progress), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Discovery follows the lifecycle, not composition: leaving the app keeps the Activity and its tree alive, and
        // discovery would keep listening for three service types, radio awake for nothing. ON_START starts, ON_STOP stops.
        LifecycleStartEffect(Unit) {
            onSearch()
            onStopOrDispose { onStopSearch() }
        }

        if (state.detected.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.discovery_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.discovery_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.detected.forEach { device ->
                        // For a device seen before, its remembered name starts with the brand.
                        val manufacturer = state.knownNames[device.host]?.let { Manufacturer.fromName(it) }
                        OutlinedButton(
                            onClick = { onConnectTo(device) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (manufacturer != null) {
                                BrandPlate(manufacturer = manufacturer, height = 26.dp)
                                Spacer(Modifier.width(12.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = device.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "${device.host}:${device.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.connection_scan_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.connection_scan_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            scanner.startScan()
                                .addOnSuccessListener { code -> code.rawValue?.let(onScan) }
                                .addOnFailureListener { error ->
                                    onScanFailure(error.message.orEmpty())
                                }
                        },
                        enabled = moduleState != ModuleState.DOWNLOADING,
                    ) {
                        Text(stringResource(R.string.connection_scan))
                    }
                    when (moduleState) {
                        ModuleState.DOWNLOADING -> {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            Text(
                                text = stringResource(R.string.connection_scan_preparing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        ModuleState.UNAVAILABLE -> Text(
                            text = stringResource(R.string.connection_scan_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Unit
                    }
                }
            }
        }

        HorizontalDivider()

        // No field takes focus by itself, so the keyboard stays closed when the screen opens.
        Text(
            text = stringResource(R.string.connection_manual_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.enteredHost,
                onValueChange = onHost,
                label = { Text(stringResource(R.string.connection_host)) },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.enteredPort,
                onValueChange = onPort,
                label = { Text(stringResource(R.string.connection_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onConnect,
                enabled = state.connection.state != ConnectionState.CONNECTION,
            ) {
                Text(stringResource(R.string.connection_connect))
            }
            if (state.connection.state == ConnectionState.CONNECTION) {
                CircularProgressIndicator()
            }
        }

        if (state.connection.state == ConnectionState.CONNECTION) {
            Text(
                text = stringResource(R.string.connection_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ErrorState(state.connection)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.connection_help_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.connection_help_1),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.connection_help_2),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.connection_help_3),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectedDevice(
    state: RemoteState,
    onDisconnect: () -> Unit,
    onReboot: () -> Unit,
    onRefresh: () -> Unit,
) {
    // Three buttons do not always fit on a phone: the one that overflows wraps whole instead of splitting its text.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDisconnect) {
            Text(stringResource(R.string.connection_disconnect))
        }
        Button(onClick = onRefresh) { Text(stringResource(R.string.action_refresh)) }
        OutlinedButton(onClick = onReboot) { Text(stringResource(R.string.device_reboot)) }
        if (state.loading) CircularProgressIndicator()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    when (state.info.deviceType) {
                        DeviceType.TV -> R.string.device_title
                        DeviceType.BOX -> R.string.device_type_box
                        DeviceType.PHONE -> R.string.device_type_phone
                        DeviceType.TABLET -> R.string.device_type_tablet
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            state.info.manufacturer?.let {
                BrandPlate(manufacturer = it, height = 34.dp, modifier = Modifier.padding(vertical = 4.dp))
            }
            Measurement(
                stringResource(R.string.device_model),
                state.info.displayName,
            )
            Measurement(stringResource(R.string.device_android), state.info.androidVersion)
            Measurement(
                stringResource(R.string.device_memory),
                stringResource(
                    R.string.device_memory_value,
                    state.info.freeMemoryMb,
                    state.info.totalMemoryMb,
                ),
            )
            Measurement(
                stringResource(R.string.device_packages_active),
                state.info.installedPackages.toString(),
            )
            Measurement(
                stringResource(R.string.device_packages_disabled),
                state.info.disabledPackages.toString(),
            )
            Measurement(
                stringResource(R.string.device_home),
                state.info.currentHome.ifBlank { "—" },
            )
        }
    }
}

@Composable
private fun Measurement(label: String, rawValue: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = rawValue, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorState(connection: ConnectionUi) {
    if (connection.state != ConnectionState.ERROR) return
    val explanation = when (connection.problem ?: ConnectionProblem.OTHER) {
        ConnectionProblem.REJECTED -> R.string.connection_problem_refused
        ConnectionProblem.TIMEOUT -> R.string.connection_problem_timeout
        ConnectionProblem.UNAUTHORIZED -> R.string.connection_problem_unauthorized
        ConnectionProblem.UNREACHABLE -> R.string.connection_problem_unreachable
        ConnectionProblem.OTHER -> R.string.connection_problem_other
    }
    Text(
        text = stringResource(explanation),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (connection.detail.isNotBlank()) {
        SelectionContainer {
            Text(
                text = connection.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
