package net.jolabs40.tvslim.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import net.jolabs40.tvslim.R
import net.jolabs40.tvslim.install.PhaseInstallation
import net.jolabs40.tvslim.ui.EtatUi
import net.jolabs40.tvslim.ui.components.Bloc
import net.jolabs40.tvslim.ui.components.EnTete
import net.jolabs40.tvslim.ui.components.QrCode

/**
 * Écran d'installation de Shizuku.
 *
 * Deux chemins, du plus court au plus manuel :
 *
 *  1. **Installation intégrée** : l'application télécharge l'APK depuis le dépôt officiel,
 *     contrôle sa signature contre l'empreinte épinglée, puis laisse le système demander
 *     confirmation. Exige Android 9 (lecture de la signature d'un APK) et l'autorisation
 *     d'installer des paquets.
 *  2. **QR codes** : Shizuku n'est pas référencée dans le Play Store du téléviseur et un
 *     téléviseur n'a en général pas de navigateur — le téléphone sert de relais.
 */
@Composable
fun ShizukuScreen(
    etat: EtatUi,
    onActualiser: () -> Unit,
    onInstaller: () -> Unit,
    intentionSourcesInconnues: () -> Intent,
) {
    val contexte = LocalContext.current
    var messageLocal by remember { mutableStateOf<String?>(null) }
    val premierBouton = remember { FocusRequester() }
    // Le premier bouton n'existe qu'une fois `installationDisponible` connu : demander le focus
    // avant qu'il soit composé le ferait atterrir sur le bouton suivant.
    LaunchedEffect(etat.installationDisponible) {
        if (etat.installationDisponible) {
            withFrameNanos { }
            runCatching { premierBouton.requestFocus() }
        }
    }

    val urlBoutique = stringResource(R.string.shizuku_url_play)
    val urlTelechargement = stringResource(R.string.shizuku_url_download)
    val boutiqueIndisponible = stringResource(R.string.shizuku_store_missing)
    val reglagesIndisponibles = stringResource(R.string.shizuku_sources_screen_missing)

    Column(modifier = Modifier.fillMaxWidth()) {
        EnTete(
            titre = stringResource(R.string.shizuku_title),
            sousTitre = stringResource(R.string.shizuku_intro),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CarteQr(
                titre = stringResource(R.string.shizuku_qr_play),
                explication = stringResource(R.string.shizuku_qr_play_hint),
                url = urlBoutique,
                modifier = Modifier.weight(1f),
            )
            CarteQr(
                titre = stringResource(R.string.shizuku_qr_download),
                explication = stringResource(R.string.shizuku_qr_download_hint),
                url = urlTelechargement,
                modifier = Modifier.weight(1f),
            )
            Bloc(modifier = Modifier.weight(1.2f)) {
                Text(
                    text = stringResource(R.string.shizuku_steps_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(10.dp))
                Etape(1, stringResource(R.string.shizuku_step1))
                Etape(2, stringResource(R.string.shizuku_step2))
                Etape(3, stringResource(R.string.shizuku_step3))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.shizuku_signature_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (etat.installationDisponible) {
                Button(
                    onClick = {
                        messageLocal = null
                        if (etat.sourcesInconnuesAutorisees) {
                            onInstaller()
                        } else {
                            messageLocal = lancer(
                                contexte = contexte,
                                intention = intentionSourcesInconnues(),
                                messageEchec = reglagesIndisponibles,
                            )
                        }
                    },
                    modifier = Modifier.focusRequester(premierBouton),
                ) {
                    Text(
                        stringResource(
                            if (etat.sourcesInconnuesAutorisees) {
                                R.string.shizuku_install_direct
                            } else {
                                R.string.shizuku_allow_sources
                            },
                        ),
                    )
                }
            }
            Button(
                onClick = {
                    messageLocal = ouvrirBoutique(contexte, urlBoutique, boutiqueIndisponible)
                },
            ) {
                Text(stringResource(R.string.shizuku_open_store))
            }
            Button(onClick = onActualiser) { Text(stringResource(R.string.action_refresh)) }
        }

        Spacer(Modifier.height(10.dp))
        LigneEtat(etat = etat, messageLocal = messageLocal)
    }
}

@Composable
private fun LigneEtat(etat: EtatUi, messageLocal: String?) {
    val installation = etat.installation
    val (texte, couleur) = when {
        messageLocal != null -> messageLocal to MaterialTheme.colorScheme.error

        installation.phase != PhaseInstallation.INACTIVE -> libellePhase(installation.phase, installation.pourcent) to
            couleurPhase(installation.phase)

        !etat.installationDisponible ->
            stringResource(R.string.shizuku_install_unavailable) to
                MaterialTheme.colorScheme.onSurfaceVariant

        else -> stringResource(libellePrivilege(etat.privilege)) to couleurPrivilege(etat.privilege)
    }
    val detail = etat.installation.detail

    Column {
        Text(text = texte, style = MaterialTheme.typography.bodyMedium, color = couleur)
        if (detail.isNotBlank() && messageLocal == null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun libellePhase(phase: PhaseInstallation, pourcent: Int): String = when (phase) {
    PhaseInstallation.RECHERCHE -> stringResource(R.string.phase_search)
    PhaseInstallation.TELECHARGEMENT -> stringResource(R.string.phase_download, pourcent)
    PhaseInstallation.VERIFICATION -> stringResource(R.string.phase_verify)
    PhaseInstallation.ATTENTE_CONFIRMATION -> stringResource(R.string.phase_confirm)
    PhaseInstallation.SUCCES -> stringResource(R.string.phase_success)
    PhaseInstallation.ECHEC -> stringResource(R.string.phase_failure)
    PhaseInstallation.INACTIVE -> ""
}

@Composable
private fun couleurPhase(phase: PhaseInstallation): Color = when (phase) {
    PhaseInstallation.ECHEC -> MaterialTheme.colorScheme.error
    PhaseInstallation.SUCCES -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onBackground
}

@Composable
private fun CarteQr(
    titre: String,
    explication: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    Bloc(modifier = modifier) {
        Text(text = titre, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        QrCode(
            contenu = url,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            taille = 150.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = explication,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Etape(numero: Int, texte: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$numero.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(0.06f),
        )
        Text(text = texte, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * Tente d'ouvrir la fiche Shizuku dans le Play Store du téléviseur. La plupart des Android TV
 * n'ont pas de navigateur et le Play Store TV masque les applications qui ne ciblent pas la
 * télévision : l'échec est un cas normal, pas une anomalie.
 */
private fun ouvrirBoutique(contexte: Context, url: String, messageEchec: String): String? {
    val tentatives = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PAQUET_SHIZUKU")),
        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
    )
    tentatives.forEach { intention ->
        if (lancer(contexte, intention, messageEchec) == null) return null
    }
    return messageEchec
}

private fun lancer(contexte: Context, intention: Intent, messageEchec: String): String? = try {
    contexte.startActivity(intention.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    null
} catch (_: ActivityNotFoundException) {
    messageEchec
}

private const val PAQUET_SHIZUKU = "moe.shizuku.privileged.api"
