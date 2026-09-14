package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_check_circle_24
import net.jolabs40.tvslim.windows.ressources.baseline_error_24
import net.jolabs40.tvslim.windows.ressources.baseline_get_app_24
import net.jolabs40.tvslim.windows.ressources.install_choose
import net.jolabs40.tvslim.windows.ressources.install_drop
import net.jolabs40.tvslim.windows.ressources.install_drop_disconnected
import net.jolabs40.tvslim.windows.ressources.install_examining
import net.jolabs40.tvslim.windows.ressources.install_hint
import net.jolabs40.tvslim.windows.ressources.install_installing
import net.jolabs40.tvslim.windows.ressources.install_last_failure
import net.jolabs40.tvslim.windows.ressources.install_last_success
import net.jolabs40.tvslim.windows.ressources.install_sending
import net.jolabs40.tvslim.windows.ressources.install_title
import net.jolabs40.tvslim.windows.ui.EtatInstallation
import net.jolabs40.tvslim.windows.ui.PhaseInstallation
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import net.jolabs40.tvslim.windows.ui.megaoctets
import net.jolabs40.tvslim.windows.ui.ressource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Installe sur le téléviseur une application qu'on a sous la main, en APK — choisie dans l'Explorateur ou
 * glissée dans la fenêtre. Ce que fait `adb install`, sans `adb.exe`.
 *
 * Le bilan de la dernière installation reste affiché : la bannière passe, et un refus d'Android mérite
 * d'être relu, sa réponse brute comprise.
 */
@Composable
fun CarteInstallation(etat: EtatInstallation, onChoisir: () -> Unit) {
    CarteSection(titre = stringResource(Res.string.install_title), espacement = 12.dp) {
        TexteSecondaire(stringResource(Res.string.install_hint))
        Button(onClick = onChoisir, enabled = !etat.occupee) {
            Icon(
                painter = painterResource(Res.drawable.baseline_get_app_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.install_choose))
        }

        when (val phase = etat.phase) {
            null -> etat.derniere?.let { Bilan(it) }
            PhaseInstallation.Examen -> Avancement(stringResource(Res.string.install_examining), fraction = null)
            is PhaseInstallation.Envoi -> Avancement(
                texte = stringResource(Res.string.install_sending, megaoctets(phase.envoye), megaoctets(phase.total)),
                fraction = if (phase.total > 0) phase.envoye.toFloat() / phase.total else null,
            )
            // Tout est parti : Android vérifie l'application, et le téléviseur peut demander son avis.
            PhaseInstallation.Installation -> Avancement(stringResource(Res.string.install_installing), fraction = null)
        }
    }
}

@Composable
private fun Avancement(texte: String, fraction: Float?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        TexteSecondaire(texte, petit = true)
    }
}

@Composable
private fun Bilan(resultat: ResultatInstallation) {
    val reussie = resultat is ResultatInstallation.Reussie
    val couleur = if (reussie) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painter = painterResource(if (reussie) Res.drawable.baseline_check_circle_24 else Res.drawable.baseline_error_24),
            contentDescription = null,
            tint = couleur,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (reussie) Res.string.install_last_success else Res.string.install_last_failure,
                    resultat.apk.nom,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = couleur,
            )
            TexteSecondaire(identite(resultat.apk), petit = true)
            if (resultat is ResultatInstallation.Echouee) {
                Text(text = stringResource(resultat.cause.ressource()), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer { TexteSecondaire(resultat.detail, petit = true) }
            }
        }
    }
}

/** « net.jolabs40.hippietv · 2.4.0 » */
private fun identite(apk: ApkChoisi): String =
    listOf(apk.manifeste.paquet, apk.manifeste.versionName).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * Ce que montre la fenêtre pendant qu'on y fait glisser un fichier : où il va partir, ou qu'il faut d'abord
 * se connecter.
 */
@Composable
fun VoileDepot(connecte: Boolean, nomTeleviseur: String) {
    val forme = MaterialTheme.shapes.large
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.94f), forme)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), forme),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painter = painterResource(Res.drawable.baseline_get_app_24),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (connecte) {
                    stringResource(Res.string.install_drop, nomTeleviseur)
                } else {
                    stringResource(Res.string.install_drop_disconnected)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
