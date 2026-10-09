package net.jolabs40.tvslim.remote.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.files.UploadProgress
import net.jolabs40.tvslim.files.RemotePath
import net.jolabs40.tvslim.files.START_FOLDER
import net.jolabs40.tvslim.files.RemoteEntry
import net.jolabs40.tvslim.files.ExplorerState
import net.jolabs40.tvslim.files.FolderRead
import net.jolabs40.tvslim.files.ShortcutKind
import net.jolabs40.tvslim.files.Shortcut
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.PendingUpload
import java.text.DateFormat
import java.util.Date

data class FilesActions(
    val onStart: () -> Unit,
    val onOpen: (String) -> Unit,
    val onGoUp: () -> Unit,
    val onRefresh: () -> Unit,
    val onUploadFiles: () -> Unit,
    val onUploadFolder: () -> Unit,
    val onCreateFolder: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onCancelConfirmation: () -> Unit,
    val onStop: () -> Unit,
    /** Sends the pending items to the displayed folder: examine, then confirm. */
    val onUploadHere: () -> Unit,
    val onAbandonUpload: () -> Unit,
)

/**
 * Browses the TV's folders and sends phone documents or a folder there. Back goes up, as far as internal storage.
 *
 * What to send is picked first, then where: while [pending] is pending, a banner asks to open the destination
 * folder and the bottom bar sends there. Back at internal storage drops it.
 */
@Composable
fun FilesScreen(connected: Boolean, state: ExplorerState, pending: PendingUpload?, actions: FilesActions) {
    if (!connected) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(
                text = stringResource(R.string.files_not_connected),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LaunchedEffect(Unit) { actions.onStart() }
    // Declared first, so it only gets Back once there is no folder left to go up to.
    BackHandler(enabled = pending != null) { actions.onAbandonUpload() }
    BackHandler(enabled = state.parent != null && state.path != START_FOLDER) { actions.onGoUp() }

    var newFolder by rememberSaveable { mutableStateOf(false) }
    var goTo by rememberSaveable { mutableStateOf(false) }
    val opened = state.reading is FolderRead.Read

    state.confirmation?.let { plan ->
        UploadConfirmation(plan = plan, onConfirm = actions.onConfirm, onCancel = actions.onCancelConfirmation)
    }
    if (newFolder) {
        InputDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_new_folder_label),
            action = stringResource(R.string.files_create),
            onSubmit = { name ->
                newFolder = false
                actions.onCreateFolder(name)
            },
            onCancel = { newFolder = false },
        )
    }
    if (goTo) {
        InputDialog(
            title = stringResource(R.string.files_go_to),
            label = stringResource(R.string.files_go_to_label),
            action = stringResource(R.string.files_go),
            initial = state.path,
            onSubmit = { path ->
                goTo = false
                actions.onOpen(path.trim())
            },
            onCancel = { goTo = false },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            pending?.let { PendingUploadStrip(it) }
            Shortcuts(state, actions.onOpen)
            PathBar(
                state = state,
                actions = actions,
                opened = opened,
                onNewFolder = { newFolder = true },
                onGoTo = { goTo = true },
            )
            val progress = state.progress
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                when {
                    state.review -> LabeledProgress(text = stringResource(R.string.files_examining), fraction = null)
                    progress != null -> Upload(progress, actions.onStop)
                    else -> state.last?.takeIf { it.failures.isNotEmpty() }?.let { Failures(it) }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            FileList(state, actions.onOpen)
        }
        when {
            pending != null -> DestinationBar(
                // The root has no name: shown as `/`.
                folder = RemotePath.name(state.path).ifEmpty { RemotePath.ROOT },
                active = opened && !state.busy,
                onCancel = actions.onAbandonUpload,
                onSend = actions.onUploadHere,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            opened && !state.busy -> SendToTv(
                onFiles = actions.onUploadFiles,
                onFolder = actions.onUploadFolder,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun PendingUploadStrip(waiting: PendingUpload) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (waiting.folder) Icons.Filled.DriveFolderUpload else Icons.Filled.UploadFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = when {
                        waiting.folder -> stringResource(R.string.files_pending_folder, waiting.name)
                        waiting.count == 1 -> stringResource(R.string.files_pending_file, waiting.name)
                        else -> pluralStringResource(R.plurals.files_pending_count, waiting.count, waiting.count)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.files_pending_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** Names the displayed folder, so it is clear where the files go. */
@Composable
private fun DestinationBar(
    folder: String,
    active: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.confirm_cancel)) }
            Button(onClick = onSend, enabled = active, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.files_send_into, folder),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Shortcuts(state: ExplorerState, onOpen: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.shortcuts, key = { it.path }) { shortcut ->
            FilterChip(
                selected = state.path == shortcut.path,
                onClick = { onOpen(shortcut.path) },
                label = { Text(shortcutName(shortcut)) },
                leadingIcon = {
                    Icon(shortcutIcon(shortcut.kind), contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }
    }
}

@Composable
private fun shortcutName(shortcut: Shortcut): String = when (shortcut.kind) {
    ShortcutKind.INTERNAL -> stringResource(R.string.shortcut_internal)
    ShortcutKind.DOWNLOADS -> stringResource(R.string.shortcut_downloads)
    ShortcutKind.MOVIES -> stringResource(R.string.shortcut_movies)
    ShortcutKind.MUSIC -> stringResource(R.string.shortcut_music)
    ShortcutKind.IMAGES -> stringResource(R.string.shortcut_pictures)
    ShortcutKind.VOLUME -> stringResource(R.string.shortcut_volume, shortcut.name)
    ShortcutKind.TEMPORARY -> stringResource(R.string.shortcut_temp)
    ShortcutKind.ROOT -> stringResource(R.string.shortcut_root)
}

private fun shortcutIcon(kind: ShortcutKind): ImageVector = when (kind) {
    ShortcutKind.INTERNAL -> Icons.Filled.Home
    ShortcutKind.VOLUME -> Icons.Filled.Usb
    else -> Icons.Filled.Folder
}

@Composable
private fun PathBar(
    state: ExplorerState,
    actions: FilesActions,
    opened: Boolean,
    onNewFolder: () -> Unit,
    onGoTo: () -> Unit,
) {
    val scrollState = rememberScrollState()
    // Keep the end of the breadcrumb, the current folder, in view.
    LaunchedEffect(state.path) { scrollState.scrollTo(scrollState.maxValue) }
    Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onGoUp, enabled = state.parent != null) {
            Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.files_up))
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.steps.forEachIndexed { index, step ->
                if (index > 1) {
                    Text(text = "›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = step.name,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { actions.onOpen(step.path) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onNewFolder, enabled = opened) {
            Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
        }
        IconButton(onClick = onGoTo) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.files_go_to))
        }
        IconButton(onClick = actions.onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
        }
    }
}

/** Documents or a whole folder; the destination is picked afterwards. */
@Composable
private fun SendToTv(onFiles: () -> Unit, onFolder: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ExtendedFloatingActionButton(
            onClick = { menu = true },
            icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
            text = { Text(stringResource(R.string.files_send_to_tv)) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_send_files)) },
                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                onClick = {
                    menu = false
                    onFiles()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.files_send_folder)) },
                leadingIcon = { Icon(Icons.Filled.DriveFolderUpload, contentDescription = null) },
                onClick = {
                    menu = false
                    onFolder()
                },
            )
        }
    }
}

@Composable
private fun Upload(progress: UploadProgress, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LabeledProgress(
                text = stringResource(
                    R.string.files_sending,
                    progress.file.ifBlank { "…" },
                    progress.index.coerceAtLeast(1),
                    progress.count,
                ),
                // Total size unknown: count files instead.
                fraction = if (progress.total > 0) {
                    progress.sent.toFloat() / progress.total
                } else {
                    (progress.index - 1).coerceAtLeast(0).toFloat() / progress.count.coerceAtLeast(1)
                },
            )
            Text(
                text = stringResource(
                    R.string.files_sending_bytes,
                    readableSize(progress.sent),
                    readableSize(progress.total),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onStop) { Text(stringResource(R.string.files_stop)) }
    }
}

@Composable
private fun LabeledProgress(text: String, fraction: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Failures of the last send. The banner goes away; this card stays until the next send. */
@Composable
private fun Failures(result: UploadResult) {
    // A refusal with no message from the TV gets a localized one.
    val rejected = stringResource(R.string.files_refused_silent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.files_last_failures),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            SelectionContainer {
                Text(
                    text = result.failures.take(MAX_LISTED_FAILURES).joinToString("\n") { "${it.path} : ${it.reason.ifBlank { rejected }}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun FileList(state: ExplorerState, onOpen: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val reading = state.reading) {
            null -> Waiting()
            is FolderRead.NotFound -> StatusNotice(stringResource(R.string.files_not_found))
            is FolderRead.Rejected -> StatusNotice(stringResource(R.string.files_denied))
            is FolderRead.Failed -> StatusNotice(stringResource(R.string.files_failed, reading.reason))
            is FolderRead.Read -> if (reading.entries.isEmpty()) {
                StatusNotice(stringResource(R.string.files_empty))
            } else {
                val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                // Bottom padding so neither the FAB nor the destination bar hides the last row.
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(reading.entries, key = { it.name }) { entry ->
                        EntryRow(
                            entry = entry,
                            date = format.format(Date(entry.date)),
                            onOpen = { onOpen(RemotePath.join(reading.path, entry.name)) },
                        )
                    }
                }
            }
        }
        // Reloading the same folder keeps the list on screen; a thin bar shows the reload.
        if (state.loading && state.reading != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, date: String, onOpen: () -> Unit) {
    // Dot files are hidden on Android too: listed, but dimmed.
    val color = if (entry.name.startsWith('.')) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entry.folder) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.folder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.folder) date else "${readableSize(entry.size)} · $date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.link) {
            Icon(
                Icons.Filled.Link,
                contentDescription = stringResource(R.string.files_link),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Waiting() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.files_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusNotice(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Beyond this the card would take over the page. */
private const val MAX_LISTED_FAILURES = 20
