package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.device.MemoryProcess
import net.jolabs40.tvslim.device.MemoryBreakdown
import net.jolabs40.tvslim.device.StorageBreakdown
import net.jolabs40.tvslim.device.ApplicationStorage
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.action_refresh
import net.jolabs40.tvslim.windows.resources.memory_cached
import net.jolabs40.tvslim.windows.resources.memory_free
import net.jolabs40.tvslim.windows.resources.memory_mb
import net.jolabs40.tvslim.windows.resources.memory_not_connected
import net.jolabs40.tvslim.windows.resources.memory_processes
import net.jolabs40.tvslim.windows.resources.memory_reading
import net.jolabs40.tvslim.windows.resources.memory_stop
import net.jolabs40.tvslim.windows.resources.memory_title
import net.jolabs40.tvslim.windows.resources.memory_total
import net.jolabs40.tvslim.windows.resources.memory_unavailable
import net.jolabs40.tvslim.windows.resources.memory_used
import net.jolabs40.tvslim.windows.resources.memory_view_ram
import net.jolabs40.tvslim.windows.resources.memory_view_storage
import net.jolabs40.tvslim.windows.resources.memory_zram
import net.jolabs40.tvslim.windows.resources.size_gb
import net.jolabs40.tvslim.windows.resources.storage_app_data
import net.jolabs40.tvslim.windows.resources.storage_app_detail
import net.jolabs40.tvslim.windows.resources.storage_apps
import net.jolabs40.tvslim.windows.resources.storage_audio
import net.jolabs40.tvslim.windows.resources.storage_breakdown
import net.jolabs40.tvslim.windows.resources.storage_cache
import net.jolabs40.tvslim.windows.resources.storage_downloads
import net.jolabs40.tvslim.windows.resources.storage_estimate
import net.jolabs40.tvslim.windows.resources.storage_largest
import net.jolabs40.tvslim.windows.resources.storage_other
import net.jolabs40.tvslim.windows.resources.storage_photos
import net.jolabs40.tvslim.windows.resources.storage_title
import net.jolabs40.tvslim.windows.resources.storage_videos
import net.jolabs40.tvslim.windows.ui.AppState
import net.jolabs40.tvslim.windows.ui.components.SectionCard
import net.jolabs40.tvslim.windows.ui.components.EmptyScreen
import net.jolabs40.tvslim.windows.ui.components.Gauge
import net.jolabs40.tvslim.windows.ui.components.ValueRow
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

private enum class MemoryView { RAM, STORAGE }

/**
 * RAM and storage, behind a toggle.
 *
 * The package list shows what is installed; this shows what it actually costs (what runs in RAM, what
 * takes space in storage). On a desktop each list gets its own column instead of scrolling under the cards.
 */
@Composable
fun MemoryScreen(
    state: AppState,
    onRefresh: () -> Unit,
    onRefreshStorage: () -> Unit,
    onForceStop: (String) -> Unit,
    onResetReference: () -> Unit,
) {
    if (!state.connected) {
        EmptyScreen(stringResource(Res.string.memory_not_connected))
        return
    }

    var view by remember { mutableStateOf(MemoryView.RAM) }

    // First visit for this TV: read without waiting to be asked.
    LaunchedEffect(state.connection.host) {
        if (!state.memory.populated) onRefresh()
    }
    LaunchedEffect(state.connection.host, view) {
        if (view == MemoryView.STORAGE && !state.storage.populated) onRefreshStorage()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ViewChoice(
            view = view,
            onViewSelected = { view = it },
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
        when (view) {
            MemoryView.RAM -> Ram(state, onRefresh, onForceStop, onResetReference)
            MemoryView.STORAGE -> Storage(state, onRefreshStorage)
        }
    }
}

/** RAM or storage toggle, same style as the Packages tab filters. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewChoice(view: MemoryView, onViewSelected: (MemoryView) -> Unit, modifier: Modifier = Modifier) {
    // Fixed width and no check mark, like the filters; otherwise the RAM label wraps onto two lines.
    SingleChoiceSegmentedButtonRow(modifier = modifier.width(360.dp)) {
        MemoryView.entries.forEachIndexed { index, choice ->
            SegmentedButton(
                selected = view == choice,
                onClick = { onViewSelected(choice) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = MemoryView.entries.size),
                icon = {},
            ) {
                Text(
                    stringResource(
                        if (choice == MemoryView.RAM) Res.string.memory_view_ram else Res.string.memory_view_storage,
                    ),
                )
            }
        }
    }
}

// --- RAM ------------------------------------------------------------------------------------

@Composable
private fun Ram(
    state: AppState,
    onRefresh: () -> Unit,
    onForceStop: (String) -> Unit,
    onResetReference: () -> Unit,
) {
    val memory = state.memory

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GainCard(measurements = state.measurements, onResetReference = onResetReference)
            MemoryCard(
                memory = memory,
                loading = state.memoryLoading,
                attempted = state.memoryReadAttempted,
                onRefresh = onRefresh,
            )
        }

        VerticalDivider()

        Column(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
            Text(
                text = stringResource(Res.string.memory_processes, memory.processes.size),
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(memory.processes, key = { "${it.pid}-${it.name}" }) { processes ->
                        ProcessView(
                            processes = processes,
                            knownName = state.catalog.entries.firstOrNull { it.packageName == processes.packageName }?.name,
                            totalKb = memory.totalKb,
                            onStop = { onForceStop(processes.packageName) },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(list),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun MemoryCard(
    memory: MemoryBreakdown,
    loading: Boolean,
    attempted: Boolean,
    onRefresh: () -> Unit,
) {
    SectionCard(title = stringResource(Res.string.memory_title), spacing = 6.dp) {
        when {
            memory.populated -> {
                Gauge(memory.usedKb, memory.totalKb)
                ValueRow(stringResource(Res.string.memory_total), mb(memory.totalKb))
                ValueRow(stringResource(Res.string.memory_used), mb(memory.usedKb))
                ValueRow(stringResource(Res.string.memory_free), mb(memory.freeKb))
                if (memory.cacheKb > 0) ValueRow(stringResource(Res.string.memory_cached), mb(memory.cacheKb))
                if (memory.zramKb > 0) ValueRow(stringResource(Res.string.memory_zram), mb(memory.zramKb))
            }

            // The companion shows "reading" forever when the read fails; here the failure is reported
            // and Refresh retries.
            loading || !attempted -> SecondaryText(stringResource(Res.string.memory_reading))
            else -> Text(
                text = stringResource(Res.string.memory_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(Res.string.action_refresh))
        }
    }
}

@Composable
private fun ProcessView(
    processes: MemoryProcess,
    knownName: String?,
    totalKb: Long,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = knownName ?: processes.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (knownName != null) SecondaryText(processes.name, small = true)
            }
            Text(
                text = stringResource(Res.string.memory_mb, processes.megabytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (processes.isApp) {
                TextButton(onClick = onStop) { Text(stringResource(Res.string.memory_stop)) }
            }
        }
        Gauge(processes.kilobytes, totalKb, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun mb(kilobytes: Long): String = stringResource(Res.string.memory_mb, kilobytes / 1024)

// --- Storage --------------------------------------------------------------------------------

@Composable
private fun Storage(state: AppState, onRefresh: () -> Unit) {
    val storage = state.storage
    // Hundreds of system packages weigh a few kB and say nothing: list from 1 MB up.
    val applications = storage.applications.filter { it.totalBytes >= ONE_MB }
    // Bars scale to the largest app; relative to 50 GB they would all look empty.
    val reference = applications.firstOrNull()?.totalBytes ?: 1L

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StorageCard(
                storage = storage,
                loading = state.storageLoading,
                attempted = state.storageReadAttempted,
                onRefresh = onRefresh,
            )
        }

        VerticalDivider()

        Column(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
            Text(
                text = stringResource(Res.string.storage_largest, applications.size),
                modifier = Modifier.padding(start = 20.dp, top = 20.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            SecondaryText(
                stringResource(Res.string.storage_estimate),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                small = true,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                    items(applications, key = { it.packageName }) { application ->
                        ApplicationView(
                            application = application,
                            knownName = state.catalog.entries.firstOrNull { it.packageName == application.packageName }?.name
                                ?: state.catalog.launcherName(application.packageName),
                            reference = reference,
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(list),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun StorageCard(
    storage: StorageBreakdown,
    loading: Boolean,
    attempted: Boolean,
    onRefresh: () -> Unit,
) {
    SectionCard(title = stringResource(Res.string.storage_title), spacing = 6.dp) {
        when {
            storage.populated -> {
                Gauge(storage.usedKb, storage.totalKb)
                ValueRow(stringResource(Res.string.memory_total), size(storage.totalKb * 1024))
                ValueRow(stringResource(Res.string.memory_used), size(storage.usedKb * 1024))
                ValueRow(stringResource(Res.string.memory_free), size(storage.freeKb * 1024))

                // Breakdown by type, when Android provides it.
                val detail = listOf(
                    Res.string.storage_apps to storage.applicationsBytes,
                    Res.string.storage_app_data to storage.dataBytes,
                    Res.string.storage_cache to storage.cacheBytes,
                    Res.string.storage_photos to storage.photosBytes,
                    Res.string.storage_videos to storage.videosBytes,
                    Res.string.storage_audio to storage.audioBytes,
                    Res.string.storage_downloads to storage.downloadsBytes,
                    Res.string.storage_other to storage.otherBytes,
                ).filter { it.second > 0 }
                if (detail.isNotEmpty()) {
                    SecondaryText(
                        stringResource(Res.string.storage_breakdown),
                        modifier = Modifier.padding(top = 8.dp),
                        small = true,
                    )
                    detail.forEach { (label, bytes) -> ValueRow(stringResource(label), size(bytes)) }
                }
            }

            loading || !attempted -> SecondaryText(stringResource(Res.string.memory_reading))
            else -> Text(
                text = stringResource(Res.string.memory_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(Res.string.action_refresh))
        }
    }
}

@Composable
private fun ApplicationView(application: ApplicationStorage, knownName: String?, reference: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = knownName ?: application.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (knownName != null) SecondaryText(application.packageName, small = true)
                SecondaryText(
                    stringResource(
                        Res.string.storage_app_detail,
                        size(application.applicationBytes),
                        size(application.dataBytes),
                        size(application.cacheBytes),
                    ),
                    small = true,
                )
            }
            Text(
                text = size(application.totalBytes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Gauge(application.totalBytes, reference, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Gigabytes with one decimal from 1 GB up, megabytes below. */
@Composable
private fun size(bytes: Long): String =
    if (bytes >= ONE_GB) {
        stringResource(Res.string.size_gb, "%.1f".format(Locale.getDefault(), bytes / ONE_GB.toDouble()))
    } else {
        stringResource(Res.string.memory_mb, bytes / ONE_MB)
    }

private const val ONE_MB = 1024L * 1024
private const val ONE_GB = ONE_MB * 1024
