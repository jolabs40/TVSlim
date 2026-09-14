package net.jolabs40.tvslim.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatInstallation
import net.jolabs40.tvslim.remote.ui.PhaseInstallation
import net.jolabs40.tvslim.remote.ui.megaoctets
import net.jolabs40.tvslim.remote.ui.ressource

/**
 * Installe sur le téléviseur une application qu'on a sur le téléphone, en APK — ce que fait
 * `adb install`, sans ordinateur.
 *
 * Le bilan de la dernière installation reste affiché : la bannière passe, et un refus d'Android mérite
 * d'être relu, sa réponse brute comprise.
 */
@Composable
fun CarteInstallation(etat: EtatInstallation, onChoisir: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.install_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.install_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onChoisir, enabled = !etat.occupee) {
                Icon(Icons.Filled.GetApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.install_choose))
            }

            when (val phase = etat.phase) {
                null -> etat.derniere?.let { Bilan(it) }
                PhaseInstallation.Examen -> Avancement(stringResource(R.string.install_examining), fraction = null)
                is PhaseInstallation.Envoi -> Avancement(
                    texte = stringResource(R.string.install_sending, megaoctets(phase.envoye), megaoctets(phase.total)),
                    fraction = if (phase.total > 0) phase.envoye.toFloat() / phase.total else null,
                )
                // Tout est parti : Android vérifie l'application, et le téléviseur peut demander son avis.
                PhaseInstallation.Installation ->
                    Avancement(stringResource(R.string.install_installing), fraction = null)
            }
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
        Text(
            text = texte,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bilan(resultat: ResultatInstallation) {
    val reussie = resultat is ResultatInstallation.Reussie
    val couleur = if (reussie) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            imageVector = if (reussie) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = couleur,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(
                    if (reussie) R.string.install_last_success else R.string.install_last_failure,
                    resultat.apk.nom,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = couleur,
            )
            Text(
                text = identite(resultat.apk),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (resultat is ResultatInstallation.Echouee) {
                Text(text = stringResource(resultat.cause.ressource()), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(
                        text = resultat.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** « net.jolabs40.hippietv · 2.4.0 » */
private fun identite(apk: ApkChoisi): String =
    listOf(apk.manifeste.paquet, apk.manifeste.versionName).filter { it.isNotBlank() }.joinToString(" · ")
