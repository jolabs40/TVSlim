package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.applicationtv.EtapeTv
import net.jolabs40.tvslim.applicationtv.EtatApplicationTv
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.ActionsApplicationTv
import net.jolabs40.tvslim.remote.ui.EtatApplicationTvUi

/**
 * L'application TV Slim du téléviseur : à quoi elle sert, où elle en est, et un seul bouton pour
 * l'installer, la mettre à jour ou lui rendre son autorisation.
 */
@Composable
fun CarteApplicationTv(etat: EtatApplicationTvUi, hote: String, actions: ActionsApplicationTv) {
    // Relue à chaque téléviseur : la carte ne s'affiche que face à un téléviseur ou une box.
    LaunchedEffect(hote) { actions.onLire() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.tvapp_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(text = stringResource(R.string.tvapp_body), style = MaterialTheme.typography.bodyMedium)

            val etape = etat.etape
            val situation = etat.situation
            when {
                etape != null -> Avancement(etape)
                situation == null -> Text(
                    text = stringResource(R.string.tvapp_reading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val installee = situation.installee?.versionName.orEmpty()
                    val disponible = situation.disponible?.version
                    Text(
                        text = when (situation.etat) {
                            EtatApplicationTv.ABSENTE -> stringResource(R.string.tvapp_absent)
                            EtatApplicationTv.MISE_A_JOUR -> stringResource(R.string.tvapp_update, installee, disponible.orEmpty())
                            EtatApplicationTv.SANS_AUTORISATION -> stringResource(R.string.tvapp_no_permission, installee)
                            EtatApplicationTv.A_JOUR -> stringResource(R.string.tvapp_up_to_date, installee)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    when (situation.etat) {
                        EtatApplicationTv.ABSENTE -> {
                            Text(
                                text = stringResource(R.string.tvapp_how),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = actions.onInstaller) {
                                Text(
                                    if (disponible != null) {
                                        stringResource(R.string.tvapp_install_version, disponible)
                                    } else {
                                        stringResource(R.string.tvapp_install)
                                    },
                                )
                            }
                        }
                        EtatApplicationTv.MISE_A_JOUR -> Button(onClick = actions.onInstaller) {
                            Text(stringResource(R.string.tvapp_update_action))
                        }
                        EtatApplicationTv.SANS_AUTORISATION -> Button(onClick = actions.onAutoriser) {
                            Text(stringResource(R.string.tvapp_grant_action))
                        }
                        EtatApplicationTv.A_JOUR -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun Avancement(etape: EtapeTv) {
    val (texte, fraction) = when (etape) {
        EtapeTv.Recherche -> stringResource(R.string.tvapp_step_search) to null
        is EtapeTv.Telechargement -> part(etape.recus, etape.total).let {
            stringResource(R.string.tvapp_step_download, ((it ?: 0f) * 100).toInt()) to it
        }
        EtapeTv.Verification -> stringResource(R.string.tvapp_step_verify) to null
        is EtapeTv.Envoi -> part(etape.envoye, etape.total).let {
            stringResource(R.string.tvapp_step_send, ((it ?: 0f) * 100).toInt()) to it
        }
        EtapeTv.Autorisation -> stringResource(R.string.tvapp_step_permission) to null
        EtapeTv.Gardien -> stringResource(R.string.tvapp_step_guardian) to null
    }
    Text(text = texte, style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun part(fait: Long, total: Long): Float? = if (total > 0) (fait.toFloat() / total).coerceIn(0f, 1f) else null
