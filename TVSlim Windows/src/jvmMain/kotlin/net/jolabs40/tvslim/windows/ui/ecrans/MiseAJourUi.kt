package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.InfosApp
import net.jolabs40.tvslim.windows.maj.EtatMiseAJour
import net.jolabs40.tvslim.windows.maj.ModeDistribution
import net.jolabs40.tvslim.windows.maj.PhaseMiseAJour
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.about_close
import net.jolabs40.tvslim.windows.ressources.about_data_folder
import net.jolabs40.tvslim.windows.ressources.about_description
import net.jolabs40.tvslim.windows.ressources.about_license
import net.jolabs40.tvslim.windows.ressources.about_open_source
import net.jolabs40.tvslim.windows.ressources.about_source
import net.jolabs40.tvslim.windows.ressources.about_version
import net.jolabs40.tvslim.windows.ressources.app_name
import net.jolabs40.tvslim.windows.ressources.baseline_folder_open_24
import net.jolabs40.tvslim.windows.ressources.baseline_open_in_new_24
import net.jolabs40.tvslim.windows.ressources.baseline_system_update_24
import net.jolabs40.tvslim.windows.ressources.ic_tvslim
import net.jolabs40.tvslim.windows.ressources.update_auto_check
import net.jolabs40.tvslim.windows.ressources.update_available
import net.jolabs40.tvslim.windows.ressources.update_check_now
import net.jolabs40.tvslim.windows.ressources.update_checking
import net.jolabs40.tvslim.windows.ressources.update_dev
import net.jolabs40.tvslim.windows.ressources.update_download_page
import net.jolabs40.tvslim.windows.ressources.update_downloading
import net.jolabs40.tvslim.windows.ressources.update_install
import net.jolabs40.tvslim.windows.ressources.update_installing
import net.jolabs40.tvslim.windows.ressources.update_later
import net.jolabs40.tvslim.windows.ressources.update_portable
import net.jolabs40.tvslim.windows.ressources.update_up_to_date
import net.jolabs40.tvslim.windows.ressources.update_verifying
import net.jolabs40.tvslim.windows.ressources.update_whats_new
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import net.jolabs40.tvslim.windows.ui.composants.texteDe
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private val PhaseMiseAJour.occupee: Boolean
    get() = this is PhaseMiseAJour.Recherche || this is PhaseMiseAJour.Telechargement ||
        this is PhaseMiseAJour.Verification || this is PhaseMiseAJour.Installation

/**
 * Le bandeau qui annonce une nouvelle version, en haut de la fenêtre. Il ne télécharge rien de
 * lui-même : il propose, suit l'installation une fois demandée, et se tait sur « Plus tard ».
 */
@Composable
fun BanniereMiseAJour(
    etat: EtatMiseAJour,
    onInstaller: () -> Unit,
    onPage: () -> Unit,
    onPlusTard: () -> Unit,
) {
    val maj = etat.miseAJour ?: return
    val phase = etat.phase
    if (etat.banniereEcartee && phase is PhaseMiseAJour.Disponible) return
    var notesVisibles by remember(maj.version) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painter = painterResource(Res.drawable.baseline_system_update_24), contentDescription = null)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(Res.string.update_available, maj.version.toString()),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    when (phase) {
                        is PhaseMiseAJour.Telechargement -> {
                            Text(stringResource(Res.string.update_downloading), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress = { phase.progression }, modifier = Modifier.fillMaxWidth())
                        }

                        is PhaseMiseAJour.Verification -> {
                            Text(stringResource(Res.string.update_verifying), style = MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        is PhaseMiseAJour.Installation ->
                            Text(stringResource(Res.string.update_installing), style = MaterialTheme.typography.bodySmall)

                        is PhaseMiseAJour.Echec -> Text(
                            text = texteDe(phase.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Unit
                    }
                }
                if (!phase.occupee) {
                    if (maj.notes.isNotBlank()) {
                        TextButton(onClick = { notesVisibles = !notesVisibles }) {
                            Text(stringResource(Res.string.update_whats_new))
                        }
                    }
                    if (etat.mode == ModeDistribution.INSTALLEE) {
                        Button(onClick = onInstaller) { Text(stringResource(Res.string.update_install)) }
                    } else {
                        OutlinedButton(onClick = onPage) { Text(stringResource(Res.string.update_download_page)) }
                    }
                    if (phase is PhaseMiseAJour.Disponible) {
                        TextButton(onClick = onPlusTard) { Text(stringResource(Res.string.update_later)) }
                    }
                }
            }
            if (notesVisibles) {
                SelectionContainer {
                    Text(
                        text = maj.notes,
                        modifier = Modifier.padding(start = 36.dp, top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (etat.mode == ModeDistribution.PORTABLE && !phase.occupee) {
                Text(
                    text = stringResource(Res.string.update_portable),
                    modifier = Modifier.padding(start = 36.dp, top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Version, licence, code source, dossier des données — et le réglage des mises à jour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AProposDialogue(
    etat: EtatMiseAJour,
    onFermer: () -> Unit,
    onVerifier: () -> Unit,
    onVerificationAuto: (Boolean) -> Unit,
    onInstaller: () -> Unit,
    onSource: () -> Unit,
    onDossier: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onFermer,
        icon = {
            Image(
                painter = painterResource(Res.drawable.ic_tvslim),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        },
        title = { Text(stringResource(Res.string.app_name)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(Res.string.about_version, etat.versionActuelle),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(text = stringResource(Res.string.about_description), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(Res.string.about_open_source), style = MaterialTheme.typography.bodyMedium)
                TexteSecondaire(stringResource(Res.string.about_license, InfosApp.LICENCE), petit = true)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSource) {
                        Icon(painterResource(Res.drawable.baseline_open_in_new_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_source))
                    }
                    TextButton(onClick = onDossier) {
                        Icon(painterResource(Res.drawable.baseline_folder_open_24), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.about_data_folder))
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.clickable { onVerificationAuto(!etat.verificationAuto) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = etat.verificationAuto, onCheckedChange = onVerificationAuto)
                    Text(stringResource(Res.string.update_auto_check))
                }

                val phase = etat.phase
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onVerifier, enabled = !phase.occupee) {
                        Text(stringResource(Res.string.update_check_now))
                    }
                    when (phase) {
                        PhaseMiseAJour.Recherche -> {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(stringResource(Res.string.update_checking))
                        }

                        PhaseMiseAJour.AJour -> Text(
                            text = stringResource(Res.string.update_up_to_date),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        is PhaseMiseAJour.Disponible -> Text(
                            text = stringResource(Res.string.update_available, phase.maj.version.toString()),
                            color = MaterialTheme.colorScheme.primary,
                        )

                        is PhaseMiseAJour.Echec -> Text(
                            text = texteDe(phase.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> Unit
                    }
                }

                if (phase is PhaseMiseAJour.Disponible) {
                    Button(onClick = onInstaller) {
                        Text(
                            stringResource(
                                if (etat.mode == ModeDistribution.INSTALLEE) Res.string.update_install else Res.string.update_download_page,
                            ),
                        )
                    }
                }

                when (etat.mode) {
                    ModeDistribution.DEVELOPPEMENT -> TexteSecondaire(stringResource(Res.string.update_dev), petit = true)
                    ModeDistribution.PORTABLE -> TexteSecondaire(stringResource(Res.string.update_portable), petit = true)
                    ModeDistribution.INSTALLEE -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onFermer) { Text(stringResource(Res.string.about_close)) }
        },
    )
}
