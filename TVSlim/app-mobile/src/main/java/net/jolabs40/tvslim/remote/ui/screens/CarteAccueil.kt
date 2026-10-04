package net.jolabs40.tvslim.remote.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.LauncherRecommande
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.remote.R
import net.jolabs40.tvslim.remote.ui.EtatRemote

/**
 * Écran d'accueil du téléviseur.
 *
 * Sans launcher tiers, le moteur refuse — à raison — de désactiver l'accueil d'usine. La carte montre
 * donc ce qui est installé, reconnu à son logo — le launcher recommandé en tête, marqué en vert —, et
 * chaque launcher qui n'est pas en place se choisit d'un bouton. Elle ne propose qu'un remplaçant :
 * celui du catalogue, tant qu'aucune de ses versions n'est là, avec le lien de son site. L'application
 * n'installe rien elle-même : tant qu'il n'est pas sur le Play Store, le bouton le dit ; ensuite, il
 * ouvrira sa fiche dans la boutique **du téléviseur**, et l'installation se validera à la télécommande.
 */
@Composable
fun CarteAccueil(etat: EtatRemote, onInstaller: (String) -> Unit, onDefinirAccueil: (String) -> Unit) {
    val catalogue = etat.catalogue
    val infos = etat.infos
    val usines = infos.accueilsUsine
    // Un accueil d'usine ne se montre qu'à sa place, pas une seconde fois parmi les launchers tiers.
    val installes = catalogue.recommandesDAbord(
        infos.launchersTiers.filterNot { launcher -> usines.any { it.paquet == launcher.paquet } },
    ) { it.paquet }
    // Rien à choisir pendant un chargement ou une passe de paquets : l'accueil lu pourrait être périmé.
    val libre = !etat.chargement && etat.progression == null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            AccueilActuel(catalogue = catalogue, infos = infos)
            // Un téléphone, une tablette : seul l'accueil en place se montre. Rien n'y est à recommander — Startlight est
            // un launcher de téléviseur —, et ce que la lecture des composants désactivés y prend pour des accueils d'usine
            // (restauration, Play Services, gestion du téléphone) n'en sont pas : « Utiliser » en ferait l'écran d'accueil.
            if (!infos.typeAppareil.pourLeCatalogue) return@Column

            if (infos.launchersTiers.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (installes.isNotEmpty() || usines.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.home_available),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                installes.forEach { launcher ->
                    val actuel = launcher.paquet == infos.accueilActuel
                    LigneLauncher(
                        id = catalogue.idLauncher(launcher.paquet),
                        nom = catalogue.nomLauncher(launcher.paquet),
                        paquet = launcher.paquet,
                        recommande = catalogue.launcherRecommande(launcher.paquet) != null,
                        actuel = actuel,
                        onUtiliser = { onDefinirAccueil(launcher.composant) }
                            .takeIf { !actuel && launcher.composant.isNotBlank() },
                        libre = libre,
                    )
                }
                // Google TV, l'accueil Android TV, celui du constructeur : listés même désactivés, sans
                // quoi l'accueil d'origine semblerait avoir disparu du téléviseur. Actif, il se rechoisit ;
                // désactivé, Android ne le servirait pas.
                usines.forEach { usine ->
                    val actuel = usine.paquet == infos.accueilActuel
                    LigneLauncher(
                        id = null,
                        nom = catalogue.entrees.firstOrNull { it.paquet == usine.paquet }?.nom,
                        paquet = usine.paquet,
                        recommande = false,
                        usine = true,
                        desactive = !usine.actif,
                        actuel = actuel,
                        onUtiliser = { onDefinirAccueil(usine.composant) }
                            .takeIf { !actuel && usine.actif && usine.composant.isNotBlank() },
                        libre = libre,
                    )
                }
            }

            catalogue.launchersAProposer(installes.map { it.paquet }).forEach { launcher ->
                CarteRecommandation(launcher = launcher, onInstaller = onInstaller)
            }
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.home_current_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = nom ?: paquet.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (nom != null) {
                Text(
                    text = paquet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (infos.accueilsUsine.any { it.paquet == paquet }) {
                Etiquette(stringResource(R.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
    }
}

/**
 * Un launcher : son logo, son nom, ses étiquettes sous le nom — sur un téléphone, en bout de ligne, elles
 * écraseraient le texte —, et en bout de ligne « Actuel », ou de quoi le choisir.
 */
@Composable
private fun LigneLauncher(
    id: String?,
    nom: String?,
    paquet: String,
    recommande: Boolean,
    usine: Boolean = false,
    desactive: Boolean = false,
    actuel: Boolean = false,
    onUtiliser: (() -> Unit)? = null,
    libre: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LogoLauncher(id = id, taille = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            // Le nom commercial d'abord quand le catalogue le connaît : « Projectivy Launcher »
            // parle, « com.spocky.projengmenu » beaucoup moins.
            Text(
                text = nom ?: paquet,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (desactive) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            if (nom != null) {
                Text(
                    text = paquet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Sous le nom plutôt qu'en bout de ligne : sur un téléphone, deux étiquettes écraseraient le texte.
            if (recommande || usine || desactive) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (recommande) {
                        val (fond, encre) = couleursRecommande()
                        Etiquette(stringResource(R.string.home_recommended_badge), fond, encre)
                    }
                    if (usine) {
                        Etiquette(stringResource(R.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
                    }
                    if (desactive) {
                        Etiquette(stringResource(R.string.home_disabled_badge), MaterialTheme.colorScheme.errorContainer)
                    }
                }
            }
        }
        when {
            actuel -> Etiquette(stringResource(R.string.home_current_badge), MaterialTheme.colorScheme.secondaryContainer)
            onUtiliser != null -> OutlinedButton(onClick = onUtiliser, enabled = libre) {
                Text(stringResource(R.string.home_use))
            }
        }
    }
}

/** Une étiquette arrondie : « Recommandé », « Launcher d'usine », « Désactivé », « Actuel ». */
@Composable
private fun Etiquette(texte: String, fond: Color, encre: Color = Color.Unspecified) {
    Surface(color = fond, shape = RoundedCornerShape(50)) {
        Text(
            text = texte,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = encre,
        )
    }
}

/**
 * Le vert de « Recommandé », fond et encre, lisible sur les deux thèmes : les couleurs dynamiques du
 * téléphone n'ont pas de vert, et la recommandation doit se voir quelle que soit la palette.
 */
@Composable
private fun couleursRecommande(): Pair<Color, Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1E5631) to Color(0xFFC8F2D4)
    } else {
        Color(0xFFCDEFD6) to Color(0xFF0F5223)
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
                    Text(
                        text = launcher.nom,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.home_recommended),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(text = launcher.description, style = MaterialTheme.typography.bodyMedium)
            launcher.pointsForts.forEach { point ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = point, style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Le site d'abord : on peut le découvrir là, bien avant que la boutique le propose.
            if (launcher.site.isNotBlank()) {
                val liens = LocalUriHandler.current
                Button(onClick = { runCatching { liens.openUri(launcher.site) } }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(launcher.siteAffiche)
                }
            }
            // Pas encore sur le Play Store : aucune fiche à ouvrir, et le bouton le dit.
            if (launcher.disponible) {
                Button(onClick = { onInstaller(launcher.paquet) }) {
                    Text(stringResource(R.string.home_install))
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
                    Text(stringResource(R.string.home_coming_soon))
                }
            }
        }
    }
}
