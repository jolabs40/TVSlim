package net.jolabs40.tvslim.windows.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.files.DeletionKind
import net.jolabs40.tvslim.files.UploadPlan
import net.jolabs40.tvslim.files.DownloadPlan
import net.jolabs40.tvslim.files.DeletionPlan
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.baseline_folder_24
import net.jolabs40.tvslim.windows.resources.baseline_insert_drive_file_24
import net.jolabs40.tvslim.windows.resources.confirm_cancel
import net.jolabs40.tvslim.windows.resources.file_size_bytes
import net.jolabs40.tvslim.windows.resources.file_size_gb
import net.jolabs40.tvslim.windows.resources.file_size_kb
import net.jolabs40.tvslim.windows.resources.file_size_mb
import net.jolabs40.tvslim.windows.resources.files_confirm_count
import net.jolabs40.tvslim.windows.resources.files_confirm_destination
import net.jolabs40.tvslim.windows.resources.files_confirm_existing
import net.jolabs40.tvslim.windows.resources.files_confirm_more
import net.jolabs40.tvslim.windows.resources.files_confirm_send
import net.jolabs40.tvslim.windows.resources.files_confirm_title
import net.jolabs40.tvslim.windows.resources.files_copy_confirm_copy
import net.jolabs40.tvslim.windows.resources.files_copy_confirm_destination
import net.jolabs40.tvslim.windows.resources.files_copy_confirm_existing
import net.jolabs40.tvslim.windows.resources.files_copy_confirm_source
import net.jolabs40.tvslim.windows.resources.files_copy_confirm_title
import net.jolabs40.tvslim.windows.resources.files_delete
import net.jolabs40.tvslim.windows.resources.files_delete_confirm_file
import net.jolabs40.tvslim.windows.resources.files_delete_confirm_folder
import net.jolabs40.tvslim.windows.resources.files_delete_confirm_link
import net.jolabs40.tvslim.windows.resources.files_delete_confirm_title
import net.jolabs40.tvslim.windows.resources.files_delete_confirm_warning
import net.jolabs40.tvslim.windows.ui.components.SecondaryText
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/** What will be uploaded and where. Nothing is sent without this confirmation, accidental drops included. */
@Composable
fun UploadConfirmation(plan: UploadPlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val roots = plan.batch.roots.entries.sortedWith(
        compareBy<Map.Entry<String, Boolean>> { !it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
    )
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.files_confirm_title)) },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.files_confirm_destination, plan.destination),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        Res.string.files_confirm_count,
                        plan.batch.files.size,
                        readableSize(plan.batch.size),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                roots.take(MAX_ROOTS).forEach { (name, folder) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (folder) Res.drawable.baseline_folder_24 else Res.drawable.baseline_insert_drive_file_24,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (roots.size > MAX_ROOTS) {
                    SecondaryText(stringResource(Res.string.files_confirm_more, roots.size - MAX_ROOTS), small = true)
                }
                if (plan.existing.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.files_confirm_existing, plan.existing.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.files_confirm_send)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/**
 * Confirms copying a folder to the PC: from where, to where, how much, since a movie folder easily weighs
 * tens of gigabytes. A single file needs no such step; the save dialog already served.
 */
@Composable
fun DownloadConfirmation(plan: DownloadPlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.files_copy_confirm_title)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.files_copy_confirm_source, plan.source), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(Res.string.files_copy_confirm_destination, plan.destination),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.files_confirm_count, plan.files.size, readableSize(plan.size)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (plan.alreadyExists) {
                    Text(
                        text = stringResource(Res.string.files_copy_confirm_existing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.files_copy_confirm_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/**
 * States what a delete removes (for a folder, everything in it). The TV has no recycle bin and the dialog
 * says so, except for a link, which only removes itself.
 */
@Composable
fun DeletionConfirmation(plan: DeletionPlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.files_delete_confirm_title)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (plan.kind) {
                        DeletionKind.FILE ->
                            stringResource(Res.string.files_delete_confirm_file, plan.name, readableSize(plan.size))

                        DeletionKind.FOLDER -> stringResource(
                            Res.string.files_delete_confirm_folder,
                            plan.name,
                            plan.files,
                            plan.folders,
                            readableSize(plan.size),
                        )

                        DeletionKind.LINK -> stringResource(Res.string.files_delete_confirm_link, plan.name)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                SecondaryText(plan.path, small = true)
                if (plan.kind != DeletionKind.LINK) {
                    Text(
                        text = stringResource(Res.string.files_delete_confirm_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(Res.string.files_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/** One field, one action: name a folder or type a path to go to. Enter acts as a click on the action. */
@Composable
fun InputDialog(
    title: String,
    label: String,
    action: String,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    initial: String = "",
) {
    var rawValue by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val commit = { if (rawValue.text.isNotBlank()) onSubmit(rawValue.text) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = rawValue,
                onValueChange = { rawValue = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier
                    .widthIn(min = 360.dp)
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        val entry = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (entry && event.type == KeyEventType.KeyDown) {
                            commit()
                            true
                        } else {
                            false
                        }
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = commit, enabled = rawValue.text.isNotBlank()) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Localized file size: "48.3 MB", "912 kB", "17 B". */
@Composable
fun readableSize(bytes: Long): String = when {
    bytes < KILO -> stringResource(Res.string.file_size_bytes, bytes.toInt())
    bytes < KILO * KILO -> stringResource(Res.string.file_size_kb, decimal(bytes, KILO))
    bytes < KILO * KILO * KILO -> stringResource(Res.string.file_size_mb, decimal(bytes, KILO * KILO))
    else -> stringResource(Res.string.file_size_gb, decimal(bytes, KILO * KILO * KILO))
}

private fun decimal(bytes: Long, unit: Long): String = String.format(Locale.getDefault(), "%.1f", bytes.toDouble() / unit)

/** Decimal units (1 kB = 1,000 B), as Android uses and Windows does not. */
private const val KILO = 1_000L

private const val MAX_ROOTS = 8
