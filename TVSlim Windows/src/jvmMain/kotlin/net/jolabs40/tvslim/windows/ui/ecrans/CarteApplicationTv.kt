package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import net.jolabs40.tvslim.applicationtv.EtapeTv
import net.jolabs40.tvslim.applicationtv.EtatApplicationTv
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.tvapp_absent
import net.jolabs40.tvslim.windows.ressources.tvapp_body
import net.jolabs40.tvslim.windows.ressources.tvapp_grant_action
import net.jolabs40.tvslim.windows.ressources.tvapp_how
import net.jolabs40.tvslim.windows.ressources.tvapp_install
import net.jolabs40.tvslim.windows.ressources.tvapp_install_version
import net.jolabs40.tvslim.windows.ressources.tvapp_no_permission
import net.jolabs40.tvslim.windows.ressources.tvapp_reading
import net.jolabs40.tvslim.windows.ressources.tvapp_step_download
import net.jolabs40.tvslim.windows.ressources.tvapp_step_guardian
import net.jolabs40.tvslim.windows.ressources.tvapp_step_permission
import net.jolabs40.tvslim.windows.ressources.tvapp_step_search
import net.jolabs40.tvslim.windows.ressources.tvapp_step_send
import net.jolabs40.tvslim.windows.ressources.tvapp_step_verify
import net.jolabs40.tvslim.windows.ressources.tvapp_title
import net.jolabs40.tvslim.windows.ressources.tvapp_up_to_date
import net.jolabs40.tvslim.windows.ressources.tvapp_update
import net.jolabs40.tvslim.windows.ressources.tvapp_update_action
import net.jolabs40.tvslim.windows.ui.ActionsApplicationTv
import net.jolabs40.tvslim.windows.ui.EtatApplicationTvUi
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.stringResource

/**
 * The TV Slim app on the TV: what it does, its status, and a single button to install, update or
 * re-authorize it. Same card as on the phone.
 */
@Composable
fun CarteApplicationTv(etat: EtatApplicationTvUi, hote: String, actions: ActionsApplicationTv) {
    // Re-read for each TV; the card is only shown for a TV or a box.
    LaunchedEffect(hote) { actions.onLire() }

    CarteSection(titre = stringResource(Res.string.tvapp_title)) {
        Text(text = stringResource(Res.string.tvapp_body), style = MaterialTheme.typography.bodyMedium)

        val etape = etat.etape
        val situation = etat.situation
        when {
            etape != null -> Avancement(etape)
            situation == null -> TexteSecondaire(stringResource(Res.string.tvapp_reading))
            else -> {
                val installee = situation.installee?.versionName.orEmpty()
                val disponible = situation.disponible?.version
                Text(
                    text = when (situation.etat) {
                        EtatApplicationTv.ABSENTE -> stringResource(Res.string.tvapp_absent)
                        EtatApplicationTv.MISE_A_JOUR -> stringResource(Res.string.tvapp_update, installee, disponible.orEmpty())
                        EtatApplicationTv.SANS_AUTORISATION -> stringResource(Res.string.tvapp_no_permission, installee)
                        EtatApplicationTv.A_JOUR -> stringResource(Res.string.tvapp_up_to_date, installee)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                when (situation.etat) {
                    EtatApplicationTv.ABSENTE -> {
                        TexteSecondaire(stringResource(Res.string.tvapp_how))
                        Button(onClick = actions.onInstaller) {
                            Text(
                                if (disponible != null) {
                                    stringResource(Res.string.tvapp_install_version, disponible)
                                } else {
                                    stringResource(Res.string.tvapp_install)
                                },
                            )
                        }
                    }
                    EtatApplicationTv.MISE_A_JOUR -> Button(onClick = actions.onInstaller) {
                        Text(stringResource(Res.string.tvapp_update_action))
                    }
                    EtatApplicationTv.SANS_AUTORISATION -> Button(onClick = actions.onAutoriser) {
                        Text(stringResource(Res.string.tvapp_grant_action))
                    }
                    EtatApplicationTv.A_JOUR -> Unit
                }
            }
        }
    }
}

@Composable
private fun Avancement(etape: EtapeTv) {
    val (texte, fraction) = when (etape) {
        EtapeTv.Recherche -> stringResource(Res.string.tvapp_step_search) to null
        is EtapeTv.Telechargement -> part(etape.recus, etape.total).let {
            stringResource(Res.string.tvapp_step_download, ((it ?: 0f) * 100).toInt()) to it
        }
        EtapeTv.Verification -> stringResource(Res.string.tvapp_step_verify) to null
        is EtapeTv.Envoi -> part(etape.envoye, etape.total).let {
            stringResource(Res.string.tvapp_step_send, ((it ?: 0f) * 100).toInt()) to it
        }
        EtapeTv.Autorisation -> stringResource(Res.string.tvapp_step_permission) to null
        EtapeTv.Gardien -> stringResource(Res.string.tvapp_step_guardian) to null
    }
    Text(text = texte, style = MaterialTheme.typography.bodyMedium)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

private fun part(fait: Long, total: Long): Float? = if (total > 0) (fait.toFloat() / total).coerceIn(0f, 1f) else null
