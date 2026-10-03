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
import net.jolabs40.tvslim.fichiers.PlanDepot
import net.jolabs40.tvslim.remote.R
import java.util.Locale

/** Ce qui va partir, et où : rien ne part sans cette confirmation. */
@Composable
fun ConfirmationDepot(plan: PlanDepot, onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    val racines = plan.lot.racines.entries.sortedWith(
        compareBy<Map.Entry<String, Boolean>> { !it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
    )
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(R.string.files_confirm_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.files_confirm_destination, plan.destination))
                Text(stringResource(R.string.files_confirm_count, plan.lot.fichiers.size, tailleLisible(plan.lot.taille)))
                racines.take(RACINES_MAX).forEach { (nom, dossier) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (dossier) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = nom, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (racines.size > RACINES_MAX) {
                    Text(
                        text = stringResource(R.string.files_confirm_more, racines.size - RACINES_MAX),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plan.existants.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.files_confirm_existing, plan.existants.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmer) { Text(stringResource(R.string.files_confirm_send)) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(R.string.confirm_cancel)) }
        },
    )
}

/**
 * Un champ, une action : nommer un dossier, ou taper le chemin où aller. Le clavier ne corrige ni ne
 * capitalise — un nom de dossier ou un chemin n'est pas une phrase —, et sa touche d'action valide.
 */
@Composable
fun DialogueSaisie(
    titre: String,
    libelle: String,
    action: String,
    onValider: (String) -> Unit,
    onAnnuler: () -> Unit,
    initial: String = "",
) {
    var valeur by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val valider = { if (valeur.text.isNotBlank()) onValider(valeur.text) }

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = {
            OutlinedTextField(
                value = valeur,
                onValueChange = { valeur = it },
                label = { Text(libelle) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { valider() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = valider, enabled = valeur.text.isNotBlank()) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(R.string.confirm_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Une taille de fichier à la façon de la langue : « 48,3 Mo », « 912 ko », « 17 o ». */
@Composable
fun tailleLisible(octets: Long): String = when {
    octets < KILO -> stringResource(R.string.file_size_bytes, octets.toInt())
    octets < KILO * KILO -> stringResource(R.string.file_size_kb, decimale(octets, KILO))
    octets < KILO * KILO * KILO -> stringResource(R.string.file_size_mb, decimale(octets, KILO * KILO))
    else -> stringResource(R.string.file_size_gb, decimale(octets, KILO * KILO * KILO))
}

private fun decimale(octets: Long, unite: Long): String = String.format(Locale.getDefault(), "%.1f", octets.toDouble() / unite)

/** Les unités décimales d'Android : 1 ko = 1 000 o. */
private const val KILO = 1_000L

private const val RACINES_MAX = 8
