package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.action_refresh
import net.jolabs40.tvslim.windows.ressources.baseline_refresh_24
import net.jolabs40.tvslim.windows.ressources.connection_disconnect
import net.jolabs40.tvslim.windows.ressources.device_android
import net.jolabs40.tvslim.windows.ressources.device_home
import net.jolabs40.tvslim.windows.ressources.device_memory
import net.jolabs40.tvslim.windows.ressources.device_memory_value
import net.jolabs40.tvslim.windows.ressources.device_model
import net.jolabs40.tvslim.windows.ressources.device_packages_active
import net.jolabs40.tvslim.windows.ressources.device_packages_disabled
import net.jolabs40.tvslim.windows.ressources.device_title
import net.jolabs40.tvslim.windows.ressources.home_available
import net.jolabs40.tvslim.windows.ressources.home_current
import net.jolabs40.tvslim.windows.ressources.home_install
import net.jolabs40.tvslim.windows.ressources.home_none
import net.jolabs40.tvslim.windows.ressources.home_others
import net.jolabs40.tvslim.windows.ressources.home_title
import net.jolabs40.tvslim.windows.ressources.home_to_install
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.LigneValeur
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Le téléviseur joint : de quoi il s'agit, et de quoi le relire. */
@Composable
fun CarteAppareil(
    etat: EtatApp,
    onDeconnecter: () -> Unit,
    onActualiser: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDeconnecter) {
            Text(stringResource(Res.string.connection_disconnect))
        }
        Button(onClick = onActualiser, enabled = !etat.chargement) {
            Icon(
                painter = painterResource(Res.drawable.baseline_refresh_24),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.action_refresh))
        }
        if (etat.chargement) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
    }

    CarteSection(titre = stringResource(Res.string.device_title), espacement = 6.dp) {
        LigneValeur(stringResource(Res.string.device_model), "${etat.infos.marque} ${etat.infos.modele}".trim())
        LigneValeur(stringResource(Res.string.device_android), etat.infos.versionAndroid)
        LigneValeur(
            stringResource(Res.string.device_memory),
            stringResource(Res.string.device_memory_value, etat.infos.memoireLibreMo, etat.infos.memoireTotaleMo),
        )
        LigneValeur(stringResource(Res.string.device_packages_active), etat.infos.paquetsInstalles.toString())
        LigneValeur(stringResource(Res.string.device_packages_disabled), etat.infos.paquetsDesactives.toString())
        LigneValeur(stringResource(Res.string.device_home), etat.infos.accueilActuel.ifBlank { "—" })
    }
}

/**
 * Écran d'accueil du téléviseur.
 *
 * Sans launcher tiers, le moteur refuse — à raison — de désactiver l'accueil d'usine : l'appareil
 * démarrerait sur du vide. Plutôt que de laisser ce refus sans issue, on propose d'installer un
 * remplaçant. L'application n'installe rien elle-même : elle ouvre la fiche dans la boutique **du
 * téléviseur**, et l'installation se valide à la télécommande.
 */
@Composable
fun CarteAccueil(etat: EtatApp, onInstaller: (String) -> Unit) {
    val launchersTiers = etat.infos.launchersTiers

    CarteSection(titre = stringResource(Res.string.home_title)) {
        TexteSecondaire(
            stringResource(Res.string.home_current, etat.infos.accueilActuel.ifBlank { "—" }),
            petit = true,
        )
        if (launchersTiers.isEmpty()) {
            Text(
                text = stringResource(Res.string.home_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            TexteSecondaire(stringResource(Res.string.home_available))
            launchersTiers.forEach { launcher ->
                // Le nom commercial d'abord quand le catalogue le connaît.
                etat.catalogue.launchers.firstOrNull { it.paquet == launcher.paquet }?.nom?.let { nom ->
                    Text(text = nom, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                TexteSecondaire(launcher.paquet, petit = true)
            }
        }

        // Ceux du catalogue qui ne sont pas encore là, proposés même quand un launcher tiers existe
        // déjà : en avoir un n'empêche pas d'en vouloir essayer un autre.
        val aProposer = etat.catalogue.launchers
            .filterNot { propose -> launchersTiers.any { it.paquet == propose.paquet } }

        if (aProposer.isNotEmpty()) {
            Text(
                text = stringResource(if (launchersTiers.isEmpty()) Res.string.home_to_install else Res.string.home_others),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            aProposer.forEach { launcher ->
                Text(text = launcher.nom, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                TexteSecondaire(launcher.description, petit = true)
                OutlinedButton(onClick = { onInstaller(launcher.paquet) }) {
                    Text(stringResource(Res.string.home_install))
                }
            }
        }
    }
}
