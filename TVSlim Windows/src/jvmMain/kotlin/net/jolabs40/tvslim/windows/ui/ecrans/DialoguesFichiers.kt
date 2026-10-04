package net.jolabs40.tvslim.windows.ui.ecrans

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
import net.jolabs40.tvslim.fichiers.NatureSuppression
import net.jolabs40.tvslim.fichiers.PlanDepot
import net.jolabs40.tvslim.fichiers.PlanRapatriement
import net.jolabs40.tvslim.fichiers.PlanSuppression
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_folder_24
import net.jolabs40.tvslim.windows.ressources.baseline_insert_drive_file_24
import net.jolabs40.tvslim.windows.ressources.confirm_cancel
import net.jolabs40.tvslim.windows.ressources.file_size_bytes
import net.jolabs40.tvslim.windows.ressources.file_size_gb
import net.jolabs40.tvslim.windows.ressources.file_size_kb
import net.jolabs40.tvslim.windows.ressources.file_size_mb
import net.jolabs40.tvslim.windows.ressources.files_confirm_count
import net.jolabs40.tvslim.windows.ressources.files_confirm_destination
import net.jolabs40.tvslim.windows.ressources.files_confirm_existing
import net.jolabs40.tvslim.windows.ressources.files_confirm_more
import net.jolabs40.tvslim.windows.ressources.files_confirm_send
import net.jolabs40.tvslim.windows.ressources.files_confirm_title
import net.jolabs40.tvslim.windows.ressources.files_copy_confirm_copy
import net.jolabs40.tvslim.windows.ressources.files_copy_confirm_destination
import net.jolabs40.tvslim.windows.ressources.files_copy_confirm_existing
import net.jolabs40.tvslim.windows.ressources.files_copy_confirm_source
import net.jolabs40.tvslim.windows.ressources.files_copy_confirm_title
import net.jolabs40.tvslim.windows.ressources.files_delete
import net.jolabs40.tvslim.windows.ressources.files_delete_confirm_file
import net.jolabs40.tvslim.windows.ressources.files_delete_confirm_folder
import net.jolabs40.tvslim.windows.ressources.files_delete_confirm_link
import net.jolabs40.tvslim.windows.ressources.files_delete_confirm_title
import net.jolabs40.tvslim.windows.ressources.files_delete_confirm_warning
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.util.Locale

/** Ce qui va partir, et où : rien ne part sans cette confirmation, un dépôt glissé par mégarde compris. */
@Composable
fun ConfirmationDepot(plan: PlanDepot, onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    val racines = plan.lot.racines.entries.sortedWith(
        compareBy<Map.Entry<String, Boolean>> { !it.value }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.key },
    )
    AlertDialog(
        onDismissRequest = onAnnuler,
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
                        plan.lot.fichiers.size,
                        tailleLisible(plan.lot.taille),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                racines.take(RACINES_MAX).forEach { (nom, dossier) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (dossier) Res.drawable.baseline_folder_24 else Res.drawable.baseline_insert_drive_file_24,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = nom, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (racines.size > RACINES_MAX) {
                    TexteSecondaire(stringResource(Res.string.files_confirm_more, racines.size - RACINES_MAX), petit = true)
                }
                if (plan.existants.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.files_confirm_existing, plan.existants.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmer) { Text(stringResource(Res.string.files_confirm_send)) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/**
 * La copie d'un dossier vers le PC : d'où, vers où, combien. Un dossier de films pèse vite des dizaines de
 * gigaoctets ; celle d'un fichier n'a pas besoin de ceci, « Enregistrer sous » en a tenu lieu.
 */
@Composable
fun ConfirmationRapatriement(plan: PlanRapatriement, onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(Res.string.files_copy_confirm_title)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.files_copy_confirm_source, plan.source), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(Res.string.files_copy_confirm_destination, plan.destination),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(Res.string.files_confirm_count, plan.fichiers.size, tailleLisible(plan.taille)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (plan.existant) {
                    Text(
                        text = stringResource(Res.string.files_copy_confirm_existing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirmer) { Text(stringResource(Res.string.files_copy_confirm_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/**
 * Ce qu'une suppression emporte, dit avant qu'elle parte : pour un dossier, tout ce qu'il contient. Le téléviseur
 * n'a pas de corbeille, et la confirmation le rappelle — sauf pour un lien, qui n'emporte que lui-même.
 */
@Composable
fun ConfirmationSuppression(plan: PlanSuppression, onConfirmer: () -> Unit, onAnnuler: () -> Unit) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(stringResource(Res.string.files_delete_confirm_title)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (plan.nature) {
                        NatureSuppression.FICHIER ->
                            stringResource(Res.string.files_delete_confirm_file, plan.nom, tailleLisible(plan.taille))

                        NatureSuppression.DOSSIER -> stringResource(
                            Res.string.files_delete_confirm_folder,
                            plan.nom,
                            plan.fichiers,
                            plan.dossiers,
                            tailleLisible(plan.taille),
                        )

                        NatureSuppression.LIEN -> stringResource(Res.string.files_delete_confirm_link, plan.nom)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                TexteSecondaire(plan.chemin, petit = true)
                if (plan.nature != NatureSuppression.LIEN) {
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
                onClick = onConfirmer,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(Res.string.files_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
}

/** Un champ, une action : nommer un dossier, ou taper le chemin où aller. Entrée vaut un clic sur l'action. */
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
                modifier = Modifier
                    .widthIn(min = 360.dp)
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { evenement ->
                        val entree = evenement.key == Key.Enter || evenement.key == Key.NumPadEnter
                        if (entree && evenement.type == KeyEventType.KeyDown) {
                            valider()
                            true
                        } else {
                            false
                        }
                    },
            )
        },
        confirmButton = {
            TextButton(onClick = valider, enabled = valeur.text.isNotBlank()) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onAnnuler) { Text(stringResource(Res.string.confirm_cancel)) }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Une taille de fichier à la façon de la langue : « 48,3 Mo », « 912 ko », « 17 o ». */
@Composable
fun tailleLisible(octets: Long): String = when {
    octets < KILO -> stringResource(Res.string.file_size_bytes, octets.toInt())
    octets < KILO * KILO -> stringResource(Res.string.file_size_kb, decimale(octets, KILO))
    octets < KILO * KILO * KILO -> stringResource(Res.string.file_size_mb, decimale(octets, KILO * KILO))
    else -> stringResource(Res.string.file_size_gb, decimale(octets, KILO * KILO * KILO))
}

private fun decimale(octets: Long, unite: Long): String = String.format(Locale.getDefault(), "%.1f", octets.toDouble() / unite)

/** Les tailles en unités décimales, comme Windows ne le fait pas mais comme le fait Android : 1 ko = 1 000 o. */
private const val KILO = 1_000L

private const val RACINES_MAX = 8
