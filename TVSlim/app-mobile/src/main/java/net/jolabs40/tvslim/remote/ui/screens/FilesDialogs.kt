package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.files.UploadPlan
import net.jolabs40.tvslim.remote.R
import java.util.Locale

/** Nothing is sent without this confirmation. */
@Composable
fun UploadConfirmation(plan: UploadPlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val roots = plan.batch.roots.entries.sortedWith(
        compareBy<Map.Entry<String, Boolean>> { !it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
    )
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.files_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.files_confirm_destination, plan.destination))
                Text(stringResource(R.string.files_confirm_count, plan.batch.files.size, readableSize(plan.batch.size)))
                roots.take(MAX_ROOTS).forEach { (name, folder) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (folder) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (roots.size > MAX_ROOTS) {
                    Text(
                        text = stringResource(R.string.files_confirm_more, roots.size - MAX_ROOTS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plan.existing.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.files_confirm_existing, plan.existing.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.files_confirm_send)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.confirm_cancel)) }
        },
    )
}

/** One-field dialog for a folder name or a path. No autocorrect or capitalization; the IME action submits. */
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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = commit, enabled = rawValue.text.isNotBlank()) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.confirm_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Localized file size, such as `48.3 MB`, `912 kB` or `17 B`. */
@Composable
fun readableSize(bytes: Long): String = when {
    bytes < KILO -> stringResource(R.string.file_size_bytes, bytes.toInt())
    bytes < KILO * KILO -> stringResource(R.string.file_size_kb, decimal(bytes, KILO))
    bytes < KILO * KILO * KILO -> stringResource(R.string.file_size_mb, decimal(bytes, KILO * KILO))
    else -> stringResource(R.string.file_size_gb, decimal(bytes, KILO * KILO * KILO))
}

private fun decimal(bytes: Long, unit: Long): String = String.format(Locale.getDefault(), "%.1f", bytes.toDouble() / unit)

/** Decimal units, as Android uses them: 1 kB = 1000 B. */
private const val KILO = 1_000L

private const val MAX_ROOTS = 8
