package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.files.UploadProgress
import net.jolabs40.tvslim.files.RemotePath
import net.jolabs40.tvslim.files.RemoteEntry
import net.jolabs40.tvslim.files.ExplorerState
import net.jolabs40.tvslim.files.FolderRead
import net.jolabs40.tvslim.files.EntryKind
import net.jolabs40.tvslim.files.ShortcutKind
import net.jolabs40.tvslim.files.Shortcut
import net.jolabs40.tvslim.files.UploadResult
import net.jolabs40.tvslim.files.TransferDirection
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.action_refresh
import net.jolabs40.tvslim.windows.resources.baseline_arrow_upward_24
import net.jolabs40.tvslim.windows.resources.baseline_create_new_folder_24
import net.jolabs40.tvslim.windows.resources.baseline_delete_24
import net.jolabs40.tvslim.windows.resources.baseline_download_24
import net.jolabs40.tvslim.windows.resources.baseline_drive_folder_upload_24
import net.jolabs40.tvslim.windows.resources.baseline_edit_24
import net.jolabs40.tvslim.windows.resources.baseline_folder_24
import net.jolabs40.tvslim.windows.resources.baseline_folder_open_24
import net.jolabs40.tvslim.windows.resources.baseline_home_24
import net.jolabs40.tvslim.windows.resources.baseline_insert_drive_file_24
import net.jolabs40.tvslim.windows.resources.baseline_link_24
import net.jolabs40.tvslim.windows.resources.baseline_refresh_24
import net.jolabs40.tvslim.windows.resources.baseline_upload_file_24
import net.jolabs40.tvslim.windows.resources.baseline_usb_24
import net.jolabs40.tvslim.windows.resources.files_refused_silent
import net.jolabs40.tvslim.windows.resources.files_copy
import net.jolabs40.tvslim.windows.resources.files_copying
import net.jolabs40.tvslim.windows.resources.files_create
import net.jolabs40.tvslim.windows.resources.files_delete
import net.jolabs40.tvslim.windows.resources.files_deleting
import net.jolabs40.tvslim.windows.resources.files_denied
import net.jolabs40.tvslim.windows.resources.files_empty
import net.jolabs40.tvslim.windows.resources.files_examining
import net.jolabs40.tvslim.windows.resources.files_failed
import net.jolabs40.tvslim.windows.resources.files_go
import net.jolabs40.tvslim.windows.resources.files_go_to
import net.jolabs40.tvslim.windows.resources.files_go_to_label
import net.jolabs40.tvslim.windows.resources.files_hint
import net.jolabs40.tvslim.windows.resources.files_last_copy
import net.jolabs40.tvslim.windows.resources.files_last_failures
import net.jolabs40.tvslim.windows.resources.files_last_failures_copy
import net.jolabs40.tvslim.windows.resources.files_link
import net.jolabs40.tvslim.windows.resources.files_loading
import net.jolabs40.tvslim.windows.resources.files_new_folder
import net.jolabs40.tvslim.windows.resources.files_new_folder_label
import net.jolabs40.tvslim.windows.resources.files_not_connected
import net.jolabs40.tvslim.windows.resources.files_not_found
import net.jolabs40.tvslim.windows.resources.files_open_folder
import net.jolabs40.tvslim.windows.resources.files_reading_content
import net.jolabs40.tvslim.windows.resources.files_send_files
import net.jolabs40.tvslim.windows.resources.files_send_folder
import net.jolabs40.tvslim.windows.resources.files_sending
import net.jolabs40.tvslim.windows.resources.files_sending_bytes
import net.jolabs40.tvslim.windows.resources.files_stop
import net.jolabs40.tvslim.windows.resources.files_up
import net.jolabs40.tvslim.windows.resources.shortcut_downloads
import net.jolabs40.tvslim.windows.resources.shortcut_internal
import net.jolabs40.tvslim.windows.resources.shortcut_movies
import net.jolabs40.tvslim.windows.resources.shortcut_music
import net.jolabs40.tvslim.windows.resources.shortcut_pictures
import net.jolabs40.tvslim.windows.resources.shortcut_root
import net.jolabs40.tvslim.windows.resources.shortcut_temp
import net.jolabs40.tvslim.windows.resources.shortcut_volume
import net.jolabs40.tvslim.windows.ui.components.EmptyScreen
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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
    /** Opens the save dialog for a file or a folder picker for a folder, then copies. */
    val onCopy: (RemoteEntry) -> Unit,
    val onConfirmCopy: () -> Unit,
    val onCancelCopy: () -> Unit,
    val onDelete: (RemoteEntry) -> Unit,
    val onConfirmDeletion: () -> Unit,
    val onCancelDeletion: () -> Unit,
    /** Shows in Explorer the folder where the last copy landed. */
    val onOpenLocalFolder: (String) -> Unit,
)

/**
 * The TV's folders, Explorer style. Files dropped on the window or picked with the two buttons are uploaded to
 * the folder shown.
 *
 * Clicking a folder enters it. Files do not open, as there is nothing here to display them. Copy to PC and delete
 * appear on row hover and on right-click.
 */
@Composable
fun FilesScreen(connected: Boolean, state: ExplorerState, actions: FilesActions) {
    if (!connected) {
        EmptyScreen(stringResource(Res.string.files_not_connected))
        return
    }
    // First visit for this TV: read internal storage without waiting to be asked.
    LaunchedEffect(Unit) { actions.onStart() }

    var newFolder by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }

    state.confirmation?.let { plan ->
        UploadConfirmation(plan = plan, onConfirm = actions.onConfirm, onCancel = actions.onCancelConfirmation)
    }
    state.pendingDownload?.let { plan ->
        DownloadConfirmation(plan = plan, onConfirm = actions.onConfirmCopy, onCancel = actions.onCancelCopy)
    }
    state.deletion?.let { plan ->
        DeletionConfirmation(
            plan = plan,
            onConfirm = actions.onConfirmDeletion,
            onCancel = actions.onCancelDeletion,
        )
    }
    if (newFolder) {
        InputDialog(
            title = stringResource(Res.string.files_new_folder),
            label = stringResource(Res.string.files_new_folder_label),
            action = stringResource(Res.string.files_create),
            onSubmit = { name ->
                newFolder = false
                actions.onCreateFolder(name)
            },
            onCancel = { newFolder = false },
        )
    }
    if (goTo) {
        InputDialog(
            title = stringResource(Res.string.files_go_to),
            label = stringResource(Res.string.files_go_to_label),
            action = stringResource(Res.string.files_go),
            initial = state.path,
            onSubmit = { path ->
                goTo = false
                actions.onOpen(path.trim())
            },
            onCancel = { goTo = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Shortcuts(state, actions.onOpen)
            PathBar(state, actions, onGoTo = { goTo = true })
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // An unreadable folder accepts nothing; the upload check would refuse it anyway.
                val opened = state.reading is FolderRead.Read
                Button(onClick = actions.onUploadFiles, enabled = opened && !state.busy) {
                    ButtonIcon(Res.drawable.baseline_upload_file_24)
                    Text(stringResource(Res.string.files_send_files))
                }
                OutlinedButton(onClick = actions.onUploadFolder, enabled = opened && !state.busy) {
                    ButtonIcon(Res.drawable.baseline_drive_folder_upload_24)
                    Text(stringResource(Res.string.files_send_folder))
                }
                OutlinedButton(onClick = { newFolder = true }, enabled = opened) {
                    ButtonIcon(Res.drawable.baseline_create_new_folder_24)
                    Text(stringResource(Res.string.files_new_folder))
                }
                SecondaryText(stringResource(Res.string.files_hint), modifier = Modifier.weight(1f), small = true)
            }
            val progress = state.progress
            val last = state.last
            when {
                state.review -> LabeledProgress(text = stringResource(Res.string.files_examining), fraction = null)
                state.inventory -> LabeledProgress(text = stringResource(Res.string.files_reading_content), fraction = null)
                state.deleting -> LabeledProgress(text = stringResource(Res.string.files_deleting), fraction = null)
                progress != null -> Upload(progress, actions.onStop)
                last != null -> {
                    // Nothing arrived, nothing to show: the folder may not even exist.
                    if (last.direction == TransferDirection.DOWNLOAD && last.sentCount > 0) {
                        LastCopy(last, actions.onOpenLocalFolder)
                    }
                    if (last.failures.isNotEmpty()) Failures(last)
                }
            }
        }
        HorizontalDivider()
        FileList(state, actions)
    }
}

/** Most-used folders and mounted volumes (USB drive, SD card). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shortcuts(state: ExplorerState, onOpen: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.shortcuts.forEach { shortcut ->
            FilterChip(
                selected = state.path == shortcut.path,
                onClick = { onOpen(shortcut.path) },
                label = { Text(shortcutName(shortcut)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(shortcutIcon(shortcut.kind)),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
            )
        }
    }
}

@Composable
private fun shortcutName(shortcut: Shortcut): String = when (shortcut.kind) {
    ShortcutKind.INTERNAL -> stringResource(Res.string.shortcut_internal)
    ShortcutKind.DOWNLOADS -> stringResource(Res.string.shortcut_downloads)
    ShortcutKind.MOVIES -> stringResource(Res.string.shortcut_movies)
    ShortcutKind.MUSIC -> stringResource(Res.string.shortcut_music)
    ShortcutKind.IMAGES -> stringResource(Res.string.shortcut_pictures)
    ShortcutKind.VOLUME -> stringResource(Res.string.shortcut_volume, shortcut.name)
    ShortcutKind.TEMPORARY -> stringResource(Res.string.shortcut_temp)
    ShortcutKind.ROOT -> stringResource(Res.string.shortcut_root)
}

private fun shortcutIcon(kind: ShortcutKind): DrawableResource = when (kind) {
    ShortcutKind.INTERNAL -> Res.drawable.baseline_home_24
    ShortcutKind.VOLUME -> Res.drawable.baseline_usb_24
    else -> Res.drawable.baseline_folder_24
}

/** Up button, clickable breadcrumb, then type a path or refresh. */
@Composable
private fun PathBar(state: ExplorerState, actions: FilesActions, onGoTo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onGoUp, enabled = state.parent != null) {
            Icon(
                painter = painterResource(Res.drawable.baseline_arrow_upward_24),
                contentDescription = stringResource(Res.string.files_up),
            )
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.steps.forEachIndexed { index, step ->
                if (index > 1) SecondaryText("›")
                // A TextButton is too wide for "/": its minimum width would spread out the breadcrumb.
                Text(
                    text = step.name,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { actions.onOpen(step.path) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onGoTo) {
            Icon(
                painter = painterResource(Res.drawable.baseline_edit_24),
                contentDescription = stringResource(Res.string.files_go_to),
            )
        }
        IconButton(onClick = actions.onRefresh) {
            Icon(
                painter = painterResource(Res.drawable.baseline_refresh_24),
                contentDescription = stringResource(Res.string.action_refresh),
            )
        }
    }
}

@Composable
private fun Upload(progress: UploadProgress, onStop: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            LabeledProgress(
                text = stringResource(
                    if (progress.direction == TransferDirection.DOWNLOAD) Res.string.files_copying else Res.string.files_sending,
                    progress.file.ifBlank { "…" },
                    progress.index.coerceAtLeast(1),
                    progress.count,
                ),
                // An unknown size would not move the bar, so count files instead.
                fraction = if (progress.total > 0) {
                    progress.sent.toFloat() / progress.total
                } else {
                    (progress.index - 1).coerceAtLeast(0).toFloat() / progress.count.coerceAtLeast(1)
                },
            )
            SecondaryText(
                stringResource(Res.string.files_sending_bytes, readableSize(progress.sent), readableSize(progress.total)),
                small = true,
            )
        }
        OutlinedButton(onClick = onStop) { Text(stringResource(Res.string.files_stop)) }
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
        SecondaryText(text, small = true)
    }
}

/** Where the last copy landed, with a button to open it. */
@Composable
private fun LastCopy(result: UploadResult, onOpenLocalFolder: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SecondaryText(
            stringResource(Res.string.files_last_copy, result.sentCount, result.destination),
            modifier = Modifier.weight(1f, fill = false),
            small = true,
        )
        TextButton(onClick = { onOpenLocalFolder(result.destination) }) {
            ButtonIcon(Res.drawable.baseline_folder_open_24)
            Text(stringResource(Res.string.files_open_folder))
        }
    }
}

/** What the last upload failed to place, and why. Unlike the snackbar, this list stays until the next upload. */
@Composable
private fun Failures(result: UploadResult) {
    // A refusal with no message from the TV is worded in the UI language.
    val rejected = stringResource(Res.string.files_refused_silent)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(
                    if (result.direction == TransferDirection.DOWNLOAD) {
                        Res.string.files_last_failures_copy
                    } else {
                        Res.string.files_last_failures
                    },
                ),
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
private fun FileList(state: ExplorerState, actions: FilesActions) {
    Box(modifier = Modifier.fillMaxSize()) {
        when (val reading = state.reading) {
            null -> Waiting()
            is FolderRead.NotFound -> StatusNotice(stringResource(Res.string.files_not_found))
            is FolderRead.Rejected -> StatusNotice(stringResource(Res.string.files_denied))
            is FolderRead.Failed -> StatusNotice(stringResource(Res.string.files_failed, reading.reason))
            is FolderRead.Read -> if (reading.entries.isEmpty()) {
                StatusNotice(stringResource(Res.string.files_empty))
            } else {
                val format = remember {
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
                }
                val scrollState = rememberLazyListState()
                LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize()) {
                    items(reading.entries, key = { it.name }) { entry ->
                        EntryRow(
                            entry = entry,
                            date = format.format(Instant.ofEpochMilli(entry.date).atZone(ZoneId.systemDefault())),
                            // One operation at a time: nothing is offered during an upload, copy or delete.
                            active = !state.busy,
                            onOpen = { actions.onOpen(RemotePath.join(reading.path, entry.name)) },
                            onCopy = { actions.onCopy(entry) },
                            onDelete = { actions.onDelete(entry) },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        }
        // Re-reading the same folder keeps the list on screen; a thin progress bar is enough.
        if (state.loading && state.reading != null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/**
 * A folder entry. Copy to PC and delete appear on hover, so a hundred rows do not carry two hundred buttons,
 * and on right-click, as in Explorer.
 */
@Composable
private fun EntryRow(
    entry: RemoteEntry,
    date: String,
    active: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    // Dot files are hidden on Android too: still listed, but dimmed.
    val color = if (entry.name.startsWith('.')) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    // Pipes and device nodes cannot be copied: reading them would never end.
    val copyable = entry.kind != EntryKind.OTHER
    val copyLabel = stringResource(Res.string.files_copy)
    val deleteLabel = stringResource(Res.string.files_delete)
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()

    ContextMenuArea(
        items = {
            listOfNotNull(
                ContextMenuItem(copyLabel, onCopy).takeIf { copyable },
                ContextMenuItem(deleteLabel, onDelete),
            )
        },
        enabled = active,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactions)
                // Files do not open, but their row highlights on hover like a folder's, to show which row the
                // buttons belong to.
                .then(if (entry.folder) Modifier.clickable(onClick = onOpen) else Modifier.indication(interactions, ripple()))
                .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowContent(entry, date, color)
            // Space stays reserved so the row does not shift when the buttons appear.
            Row(
                modifier = Modifier.width(72.dp).height(32.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hovered && active) {
                    if (copyable) RowAction(Res.drawable.baseline_download_24, copyLabel, onCopy)
                    RowAction(
                        Res.drawable.baseline_delete_24,
                        deleteLabel,
                        onDelete,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 32 dp icon button with its name as a tooltip, since an icon alone is not always clear. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowAction(
    icon: DrawableResource,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = tint,
            )
        }
    }
}

@Composable
private fun RowScope.RowContent(entry: RemoteEntry, date: String, color: Color) {
    Icon(
        painter = painterResource(
            if (entry.folder) Res.drawable.baseline_folder_24 else Res.drawable.baseline_insert_drive_file_24,
        ),
        contentDescription = null,
        tint = if (entry.folder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = entry.name,
        modifier = Modifier.weight(1f),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (entry.link) {
        Icon(
            painter = painterResource(Res.drawable.baseline_link_24),
            contentDescription = stringResource(Res.string.files_link),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        text = if (entry.folder) "" else readableSize(entry.size),
        modifier = Modifier.width(90.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
    Text(
        text = date,
        modifier = Modifier.width(150.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        SecondaryText(stringResource(Res.string.files_loading))
    }
}

@Composable
private fun StatusNotice(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        SecondaryText(text)
    }
}

@Composable
private fun ButtonIcon(icon: DrawableResource) {
    Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}

/** Beyond this the card would take over the page, and the full list would add nothing. */
private const val MAX_LISTED_FAILURES = 20
