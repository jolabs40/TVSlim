package net.jolabs40.tvslim.windows.ui.ecrans

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.LauncherRecommande
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.TypeAppareil
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.action_refresh
import net.jolabs40.tvslim.windows.ressources.baseline_check_24
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
import net.jolabs40.tvslim.windows.ressources.device_type_box
import net.jolabs40.tvslim.windows.ressources.home_available
import net.jolabs40.tvslim.windows.ressources.home_coming_soon
import net.jolabs40.tvslim.windows.ressources.home_current_title
import net.jolabs40.tvslim.windows.ressources.home_factory_badge
import net.jolabs40.tvslim.windows.ressources.home_install
import net.jolabs40.tvslim.windows.ressources.home_none
import net.jolabs40.tvslim.windows.ressources.home_recommended
import net.jolabs40.tvslim.windows.ressources.home_recommended_badge
import net.jolabs40.tvslim.windows.ressources.home_title
import net.jolabs40.tvslim.windows.ressources.state_disabled
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.LigneValeur
import net.jolabs40.tvslim.windows.ui.composants.LogoLauncher
import net.jolabs40.tvslim.windows.ui.composants.PlaqueMarque
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

    val infos = etat.infos
    val titre = if (infos.typeAppareil == TypeAppareil.BOX) Res.string.device_type_box else Res.string.device_title
    CarteSection(titre = stringResource(titre), espacement = 6.dp) {
        // La marque en tête, reconnue sur ce que l'appareil déclare : voir Fabricant.
        infos.fabricant?.let { PlaqueMarque(fabricant = it, hauteur = 40.dp, modifier = Modifier.padding(bottom = 6.dp)) }
        LigneValeur(stringResource(Res.string.device_model), infos.nomAffiche)
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
 * Sans launcher tiers, le moteur refuse — à raison — de désactiver l'accueil d'usine. La carte montre
 * donc ce qui est installé, reconnu à son logo, et ne propose qu'un remplaçant : celui du catalogue,
 * tant qu'aucune de ses versions n'est là. Elle n'installe rien elle-même : tant qu'il n'est pas sur
 * le Play Store, le bouton le dit ; ensuite, il ouvrira sa fiche dans la boutique du téléviseur.
 */
@Composable
fun CarteAccueil(etat: EtatApp, onInstaller: (String) -> Unit) {
    val catalogue = etat.catalogue
    val infos = etat.infos
    val usines = infos.accueilsUsine
    // Un accueil d'usine ne se montre qu'à sa place, pas une seconde fois parmi les launchers tiers.
    val installes = infos.launchersTiers.filterNot { launcher -> usines.any { it.paquet == launcher.paquet } }

    CarteSection(titre = stringResource(Res.string.home_title), espacement = 10.dp) {
        AccueilActuel(catalogue = catalogue, infos = infos)

        if (infos.launchersTiers.isEmpty()) {
            Text(
                text = stringResource(Res.string.home_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (installes.isNotEmpty() || usines.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.home_available),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            installes.forEach { launcher ->
                LigneLauncher(
                    id = catalogue.idLauncher(launcher.paquet),
                    nom = catalogue.nomLauncher(launcher.paquet),
                    paquet = launcher.paquet,
                    recommande = catalogue.launcherRecommande(launcher.paquet) != null,
                )
            }
            // Google TV, l'accueil Android TV, celui du constructeur : listés même désactivés, sans quoi
            // l'accueil d'origine semblerait avoir disparu du téléviseur.
            usines.forEach { usine ->
                LigneLauncher(
                    id = null,
                    nom = catalogue.entrees.firstOrNull { it.paquet == usine.paquet }?.nom,
                    paquet = usine.paquet,
                    recommande = false,
                    usine = true,
                    desactive = !usine.actif,
                )
            }
        }

        catalogue.launchersAProposer(installes.map { it.paquet }).forEach { launcher ->
            CarteRecommandation(launcher = launcher, onInstaller = onInstaller)
        }
    }
}

/** L'accueil en place, en grand : c'est lui qu'on voit en allumant le téléviseur. */
@Composable
private fun AccueilActuel(catalogue: Catalogue, infos: InfosAppareil) {
    val paquet = infos.accueilActuel
    val nom = paquet.takeIf { it.isNotBlank() }?.let { actuel ->
        catalogue.nomLauncher(actuel) ?: catalogue.entrees.firstOrNull { it.paquet == actuel }?.nom
    }
    val id = paquet.takeIf { it.isNotBlank() }?.let(catalogue::idLauncher)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Plus grand que ceux de la liste, où il figure aussi.
        LogoLauncher(id = id, taille = 64.dp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TexteSecondaire(stringResource(Res.string.home_current_title), petit = true)
            Text(
                text = nom ?: paquet.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (nom != null) TexteSecondaire(paquet, petit = true)
            if (infos.accueilsUsine.any { it.paquet == paquet }) {
                Etiquette(stringResource(Res.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

@Composable
private fun LigneLauncher(
    id: String?,
    nom: String?,
    paquet: String,
    recommande: Boolean,
    usine: Boolean = false,
    desactive: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LogoLauncher(id = id, taille = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nom ?: paquet,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (desactive) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            if (nom != null) TexteSecondaire(paquet, petit = true)
        }
        if (usine) Etiquette(stringResource(Res.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
        if (desactive) Etiquette(stringResource(Res.string.state_disabled), MaterialTheme.colorScheme.errorContainer)
        if (recommande) {
            Etiquette(stringResource(Res.string.home_recommended_badge), MaterialTheme.colorScheme.primaryContainer)
        }
    }
}

/** Une étiquette arrondie : « Recommandé », « Launcher d'usine », « Désactivé ». */
@Composable
private fun Etiquette(texte: String, fond: Color) {
    Surface(color = fond, shape = RoundedCornerShape(50)) {
        Text(
            text = texte,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Le launcher que TV Slim recommande : son logo, ses points forts, et ce qu'on peut en faire. */
@Composable
private fun CarteRecommandation(launcher: LauncherRecommande, onInstaller: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LogoLauncher(id = launcher.id, taille = 56.dp)
                Column {
                    Text(text = launcher.nom, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = stringResource(Res.string.home_recommended),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(text = launcher.description, style = MaterialTheme.typography.bodyMedium)
            launcher.pointsForts.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        painter = painterResource(Res.drawable.baseline_check_24),
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = point, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Pas encore sur le Play Store : aucune fiche à ouvrir, et le bouton le dit.
            if (launcher.disponible) {
                Button(onClick = { onInstaller(launcher.paquet) }) {
                    Text(stringResource(Res.string.home_install))
                }
            } else {
                // Grisé mais lisible : les couleurs désactivées par défaut s'effacent presque sur le
                // fond de la carte.
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.35f)),
                ) {
                    Text(stringResource(Res.string.home_coming_soon))
                }
            }
        }
    }
}
