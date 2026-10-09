package net.jolabs40.tvslim.windows.ui.ecrans

import net.jolabs40.tvslim.windows.ressources.device_reboot
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.LauncherRecommande
import net.jolabs40.tvslim.device.InfosAppareil
import net.jolabs40.tvslim.device.TypeAppareil
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.action_refresh
import net.jolabs40.tvslim.windows.ressources.baseline_check_24
import net.jolabs40.tvslim.windows.ressources.baseline_open_in_new_24
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
import net.jolabs40.tvslim.windows.ressources.device_type_phone
import net.jolabs40.tvslim.windows.ressources.device_type_tablet
import net.jolabs40.tvslim.windows.ressources.home_available
import net.jolabs40.tvslim.windows.ressources.home_coming_soon
import net.jolabs40.tvslim.windows.ressources.home_current_badge
import net.jolabs40.tvslim.windows.ressources.home_current_title
import net.jolabs40.tvslim.windows.ressources.home_factory_badge
import net.jolabs40.tvslim.windows.ressources.home_install
import net.jolabs40.tvslim.windows.ressources.home_none
import net.jolabs40.tvslim.windows.ressources.home_recommended
import net.jolabs40.tvslim.windows.ressources.home_recommended_badge
import net.jolabs40.tvslim.windows.ressources.home_title
import net.jolabs40.tvslim.windows.ressources.home_use
import net.jolabs40.tvslim.windows.ressources.state_disabled
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.LigneValeur
import net.jolabs40.tvslim.windows.ui.composants.LogoLauncher
import net.jolabs40.tvslim.windows.ui.composants.PlaqueMarque
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun CarteAppareil(
    etat: EtatApp,
    onDeconnecter: () -> Unit,
    onActualiser: () -> Unit,
    onRedemarrer: () -> Unit = {},
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
        OutlinedButton(onClick = onRedemarrer, enabled = !etat.chargement) {
            Text(stringResource(Res.string.device_reboot))
        }
        if (etat.chargement) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
    }

    val infos = etat.infos
    val titre = when (infos.typeAppareil) {
        TypeAppareil.TELEVISEUR -> Res.string.device_title
        TypeAppareil.BOX -> Res.string.device_type_box
        TypeAppareil.TELEPHONE -> Res.string.device_type_phone
        TypeAppareil.TABLETTE -> Res.string.device_type_tablet
    }
    CarteSection(titre = stringResource(titre), espacement = 6.dp) {
        // Brand first, recognized from what the device reports: see Fabricant.
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
 * TV home screen.
 *
 * Without a third-party launcher the engine refuses to disable the factory home. The card shows installed
 * launchers by logo (the recommended one first, in green); any launcher not in use can be picked with a button.
 * It suggests a single replacement, the catalogue's, while none of its versions is installed, with a link to its
 * site. It installs nothing itself: until the launcher is on the Play Store the button says so, then it opens
 * the store page on the TV.
 */
@Composable
fun CarteAccueil(
    etat: EtatApp,
    onInstaller: (String) -> Unit,
    onDefinirAccueil: (String) -> Unit,
    onOuvrirLien: (String) -> Unit,
) {
    val catalogue = etat.catalogue
    val infos = etat.infos
    val usines = infos.accueilsUsine
    // A factory home appears only in its own rows, not again among third-party launchers.
    val installes = catalogue.recommandesDAbord(
        infos.launchersTiers.filterNot { launcher -> usines.any { it.paquet == launcher.paquet } },
    ) { it.paquet }
    // Nothing to pick while loading or applying packages: the home read may be stale.
    val libre = !etat.chargement && etat.progression == null

    CarteSection(titre = stringResource(Res.string.home_title), espacement = 10.dp) {
        AccueilActuel(catalogue = catalogue, infos = infos)
        // Phone or tablet: only the current home is shown. Startlight is a TV launcher, and what the
        // disabled-components query reports as factory homes there (restore, Play Services, device management)
        // are not homes: "Use" would make them the home screen.
        if (!infos.typeAppareil.pourLeCatalogue) return@CarteSection

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
            // Google TV, the Android TV home, the maker's home: listed even when disabled, or the original
            // home would seem gone. Enabled, it can be picked again; disabled, Android would not use it.
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
            CarteRecommandation(launcher = launcher, onInstaller = onInstaller, onOuvrirLien = onOuvrirLien)
        }
    }
}

/** Current home, shown large: it is what the TV shows at power-on. */
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
        // Larger than in the list, where it also appears.
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

/** Launcher row ending with "Current" or a button to pick it. */
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
            if (nom != null) TexteSecondaire(paquet, petit = true)
        }
        if (usine) Etiquette(stringResource(Res.string.home_factory_badge), MaterialTheme.colorScheme.tertiaryContainer)
        if (desactive) Etiquette(stringResource(Res.string.state_disabled), MaterialTheme.colorScheme.errorContainer)
        if (recommande) {
            val (fond, encre) = couleursRecommande()
            Etiquette(stringResource(Res.string.home_recommended_badge), fond, encre)
        }
        when {
            actuel -> Etiquette(stringResource(Res.string.home_current_badge), MaterialTheme.colorScheme.secondaryContainer)
            onUtiliser != null -> OutlinedButton(onClick = onUtiliser, enabled = libre) {
                Text(stringResource(Res.string.home_use))
            }
        }
    }
}

/** Rounded tag: "Recommended", "Factory launcher", "Disabled", "Current". */
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

/** "Recommended" green (background and text), readable on both themes, as on the phone. */
@Composable
private fun couleursRecommande(): Pair<Color, Color> =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        Color(0xFF1E5631) to Color(0xFFC8F2D4)
    } else {
        Color(0xFFCDEFD6) to Color(0xFF0F5223)
    }

/** The launcher TV Slim recommends: logo, strengths, and its actions. */
@Composable
private fun CarteRecommandation(
    launcher: LauncherRecommande,
    onInstaller: (String) -> Unit,
    onOuvrirLien: (String) -> Unit,
) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Site first: it is available there well before the store offers it.
                if (launcher.site.isNotBlank()) {
                    Button(onClick = { onOuvrirLien(launcher.site) }) {
                        Icon(
                            painter = painterResource(Res.drawable.baseline_open_in_new_24),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(launcher.siteAffiche)
                    }
                }
                // Not on the Play Store yet: no page to open, and the button says so.
                if (launcher.disponible) {
                    Button(onClick = { onInstaller(launcher.paquet) }) {
                        Text(stringResource(Res.string.home_install))
                    }
                } else {
                    // Greyed out but readable: the default disabled colors nearly vanish on the card.
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
}
