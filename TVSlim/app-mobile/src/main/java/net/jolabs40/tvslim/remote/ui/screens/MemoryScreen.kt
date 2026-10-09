package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.MemoryProcess
import net.jolabs40.tvslim.device.StorageBreakdown
import net.jolabs40.tvslim.device.ApplicationStorage
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.RemoteState
import java.util.Locale

private enum class MemoryView { RAM, STORAGE }

/**
 * RAM and storage, behind a toggle. The package list shows what is installed; this shows what it actually costs.
 * A disabled app weighs nothing, while a harmless-looking background app can take 100 MB of a 2.45 GB TV.
 */
@Composable
fun MemoryScreen(
    state: RemoteState,
    onRefresh: () -> Unit,
    onRefreshStorage: () -> Unit,
    onForceStop: (String) -> Unit,
    onResetReference: () -> Unit,
) {
    if (!state.connected) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.packages_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var view by rememberSaveable { mutableStateOf(MemoryView.RAM) }

    LaunchedEffect(state.connection.host) {
        if (!state.memory.populated) onRefresh()
    }
    LaunchedEffect(state.connection.host, view) {
        if (view == MemoryView.STORAGE && !state.storage.populated) onRefreshStorage()
    }

    val memory = state.memory
    val storage = state.storage
    // Hundreds of system packages weigh a few kB each: list only from 1 MB up.
    val applications = storage.applications.filter { it.totalBytes >= ONE_MB }

    // Everything scrolls together: on a phone, two fixed cards would leave the list almost no room.
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            ViewChoice(view = view, onViewSelected = { view = it })
        }

        when (view) {
            MemoryView.RAM -> {
                item {
                    GainCard(measurements = state.measurements, onResetReference = onResetReference)
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.memory_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )

                            if (memory.populated) {
                                Gauge(memory.usedKb, memory.totalKb)
                                LabelValueRow(stringResource(R.string.memory_total), mb(memory.totalKb))
                                LabelValueRow(stringResource(R.string.memory_used), mb(memory.usedKb))
                                LabelValueRow(stringResource(R.string.memory_free), mb(memory.freeKb))
                                if (memory.cacheKb > 0) {
                                    LabelValueRow(stringResource(R.string.memory_cached), mb(memory.cacheKb))
                                }
                                if (memory.zramKb > 0) {
                                    LabelValueRow(stringResource(R.string.memory_zram), mb(memory.zramKb))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.memory_reading),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Button(onClick = onRefresh, enabled = !state.memoryLoading) {
                                Text(stringResource(R.string.action_refresh))
                            }
                        }
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.memory_processes, memory.processes.size),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                items(memory.processes, key = { "${it.pid}-${it.name}" }) { processes ->
                    ProcessView(
                        processes = processes,
                        knownName = state.catalog.entries
                            .firstOrNull { it.packageName == processes.packageName }
                            ?.name,
                        totalKb = memory.totalKb,
                        onStop = { onForceStop(processes.packageName) },
                    )
                }
            }

            MemoryView.STORAGE -> {
                item {
                    StorageCard(
                        storage = storage,
                        loading = state.storageLoading,
                        onRefresh = onRefreshStorage,
                    )
                }

                item {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.storage_largest, applications.size),
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.storage_estimate),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Bars are relative to the largest app: against the whole disk they would all look empty.
                val reference = applications.firstOrNull()?.totalBytes ?: 1L
                items(applications, key = { it.packageName }) { application ->
                    ApplicationView(
                        application = application,
                        knownName = state.catalog.entries.firstOrNull { it.packageName == application.packageName }?.name
                            ?: state.catalog.launcherName(application.packageName),
                        reference = reference,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewChoice(view: MemoryView, onViewSelected: (MemoryView) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp),
    ) {
        MemoryView.entries.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = view == choice,
                onClick = { onViewSelected(choice) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = MemoryView.entries.size),
                // No checkmark, like the Packages tab filters: the color marks the selection.
                icon = {},
            ) {
                Text(
                    stringResource(
                        if (choice == MemoryView.RAM) R.string.memory_view_ram else R.string.memory_view_storage,
                    ),
                )
            }
        }
    }
}

@Composable
private fun StorageCard(storage: StorageBreakdown, loading: Boolean, onRefresh: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.storage_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (storage.populated) {
                Gauge(storage.usedKb, storage.totalKb)
                LabelValueRow(stringResource(R.string.memory_total), size(storage.totalKb * 1024))
                LabelValueRow(stringResource(R.string.memory_used), size(storage.usedKb * 1024))
                LabelValueRow(stringResource(R.string.memory_free), size(storage.freeKb * 1024))

                // Breakdown by type, when Android reports one.
                val detail = listOf(
                    R.string.storage_apps to storage.applicationsBytes,
                    R.string.storage_app_data to storage.dataBytes,
                    R.string.storage_cache to storage.cacheBytes,
                    R.string.storage_photos to storage.photosBytes,
                    R.string.storage_videos to storage.videosBytes,
                    R.string.storage_audio to storage.audioBytes,
                    R.string.storage_downloads to storage.downloadsBytes,
                    R.string.storage_other to storage.otherBytes,
                ).filter { it.second > 0 }
                if (detail.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.storage_breakdown),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    detail.forEach { (label, bytes) -> LabelValueRow(stringResource(label), size(bytes)) }
                }
            } else {
                Text(
                    text = stringResource(R.string.memory_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = onRefresh, enabled = !loading) {
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

@Composable
private fun ApplicationView(application: ApplicationStorage, knownName: String?, reference: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = knownName ?: application.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (knownName != null) {
                    Text(
                        text = application.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.storage_app_detail,
                        size(application.applicationBytes),
                        size(application.dataBytes),
                        size(application.cacheBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = size(application.totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Gauge(application.totalBytes, reference)
    }
}

@Composable
private fun ProcessView(
    processes: MemoryProcess,
    knownName: String?,
    totalKb: Long,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = knownName ?: processes.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (knownName != null) {
                    Text(
                        text = processes.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.memory_mb, processes.megabytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (processes.isApp) {
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.memory_stop))
                }
            }
        }
        Gauge(processes.kilobytes, totalKb)
    }
}

@Composable
private fun Gauge(rawValue: Long, total: Long) {
    val fraction = if (total > 0) (rawValue.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun LabelValueRow(label: String, rawValue: String) {
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

private fun mb(kilobytes: Long): String = "${kilobytes / 1024} Mo"

@Composable
private fun size(bytes: Long): String =
    if (bytes >= ONE_GB) {
        stringResource(R.string.size_gb, "%.1f".format(Locale.getDefault(), bytes / ONE_GB.toDouble()))
    } else {
        stringResource(R.string.memory_mb, bytes / ONE_MB)
    }

private const val ONE_MB = 1024L * 1024
private const val ONE_GB = ONE_MB * 1024
