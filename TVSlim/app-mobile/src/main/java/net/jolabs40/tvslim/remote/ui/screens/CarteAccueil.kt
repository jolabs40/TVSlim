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
 * The TV's home screen card.
 *
 * The engine will not disable the factory home screen without a third-party launcher, so the card lists the
 * installed launchers, recommended first, each selectable. It suggests only the catalogue's launcher, while no variant
 * of it is installed. The phone installs nothing: the button opens the listing in the TV's own store, where the
 * install is confirmed with the remote.
 */
@Composable
fun CarteAccueil(etat: EtatRemote, onInstaller: (String) -> Unit, onDefinirAccueil: (String) -> Unit) {
    val catalogue = etat.catalogue
    val infos = etat.infos
    val usines = infos.accueilsUsine
    // Factory home screens have their own rows, so they are not repeated among third-party launchers.
    val installes = catalogue.recommandesDAbord(
        infos.launchersTiers.filterNot { launcher -> usines.any { it.paquet == launcher.paquet } },
    ) { it.paquet }
    // Locked while loading or applying packages: the current home screen may be stale.
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
            // Phones and tablets show only the current home screen. Startlight is a TV launcher, and the disabled HOME
            // activities found there (restore, Play Services, device management) are not real home screens.
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
                // Listed even when disabled, or the original home screen would seem to be gone. Only an
                // enabled one is selectable: Android would not serve a disabled one.
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
            // Under the name: at the end of the row, two badges would squeeze the text on a phone.
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

/** Fixed green (background, text) for the Recommended badge, readable in both themes whatever the color scheme. */
@Composable
private fun couleursRecommande(): Pair<Color, Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1E5631) to Color(0xFFC8F2D4)
    } else {
        Color(0xFFCDEFD6) to Color(0xFF0F5223)
    }

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
            // Website first: the launcher is there long before it reaches the store.
            if (launcher.site.isNotBlank()) {
                val liens = LocalUriHandler.current
                Button(onClick = { runCatching { liens.openUri(launcher.site) } }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(launcher.siteAffiche)
                }
            }
            if (launcher.disponible) {
                Button(onClick = { onInstaller(launcher.paquet) }) {
                    Text(stringResource(R.string.home_install))
                }
            } else {
                // Not on the Play Store yet. The default disabled colors nearly vanish on the card background.
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
