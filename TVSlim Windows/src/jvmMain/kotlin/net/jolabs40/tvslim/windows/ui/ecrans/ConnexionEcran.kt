package net.jolabs40.tvslim.windows.ui.ecrans

import net.jolabs40.tvslim.windows.ressources.reboot_in_progress
import net.jolabs40.tvslim.windows.ressources.device_reboot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import net.jolabs40.tvslim.device.Fabricant
import net.jolabs40.tvslim.device.TypeAppareil
import net.jolabs40.tvslim.windows.adb.ConnexionUi
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.adb.ProblemeConnexion
import net.jolabs40.tvslim.windows.reseau.AppareilDecouvert
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.baseline_connected_tv_24
import net.jolabs40.tvslim.windows.ressources.baseline_wifi_find_24
import net.jolabs40.tvslim.windows.ressources.connection_connect
import net.jolabs40.tvslim.windows.ressources.connection_help_1
import net.jolabs40.tvslim.windows.ressources.connection_help_2
import net.jolabs40.tvslim.windows.ressources.connection_help_3
import net.jolabs40.tvslim.windows.ressources.connection_help_4
import net.jolabs40.tvslim.windows.ressources.connection_help_title
import net.jolabs40.tvslim.windows.ressources.connection_host
import net.jolabs40.tvslim.windows.ressources.connection_host_hint
import net.jolabs40.tvslim.windows.ressources.connection_manual_title
import net.jolabs40.tvslim.windows.ressources.connection_port
import net.jolabs40.tvslim.windows.ressources.connection_problem_other
import net.jolabs40.tvslim.windows.ressources.connection_problem_refused
import net.jolabs40.tvslim.windows.ressources.connection_problem_timeout
import net.jolabs40.tvslim.windows.ressources.connection_problem_unauthorized
import net.jolabs40.tvslim.windows.ressources.connection_problem_unreachable
import net.jolabs40.tvslim.windows.ressources.connection_subtitle
import net.jolabs40.tvslim.windows.ressources.connection_title
import net.jolabs40.tvslim.windows.ressources.connection_waiting
import net.jolabs40.tvslim.windows.ressources.discovery_hint
import net.jolabs40.tvslim.windows.ressources.discovery_none
import net.jolabs40.tvslim.windows.ressources.discovery_searching
import net.jolabs40.tvslim.windows.ressources.discovery_title
import net.jolabs40.tvslim.windows.ressources.discovery_wireless
import net.jolabs40.tvslim.windows.ui.ActionsApplicationTv
import net.jolabs40.tvslim.windows.ui.ActionsCommande
import net.jolabs40.tvslim.windows.ui.ActionsPermissions
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.EtatApplications
import net.jolabs40.tvslim.windows.ui.EtatApplicationTvUi
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.DeuxColonnes
import net.jolabs40.tvslim.windows.ui.composants.PlaqueMarque
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * TV tab: find the device and connect, then its status, home screen, privileged permissions and APK install.
 * The companion's content, in two columns.
 *
 * No QR scanner: nothing requires the app on the TV, so discovery and typing the address are enough.
 */
@Composable
fun ConnexionEcran(
    etat: EtatApp,
    onHote: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnecter: () -> Unit,
    onDeconnecter: () -> Unit,
    onActualiser: () -> Unit,
    onInstallerLauncher: (String) -> Unit,
    onDefinirAccueil: (String) -> Unit,
    onOuvrirLien: (String) -> Unit,
    onReprendreDerive: () -> Unit,
    onChercher: () -> Unit,
    onArreterRecherche: () -> Unit,
    onConnecterA: (AppareilDecouvert) -> Unit,
    actionsPermissions: ActionsPermissions,
    etatApplications: EtatApplications,
    etatApplicationTv: EtatApplicationTvUi,
    actionsApplicationTv: ActionsApplicationTv,
    onChoisirApk: () -> Unit,
    actionsCommande: ActionsCommande,
    onRedemarrer: () -> Unit = {},
) {
    if (etat.connecte) {
        DeuxColonnes(
            gauche = {
                // Unlike the phone, which puts the action first to avoid scrolling, everything fits here:
                // device first, then its home screen. Drift comes before anything else.
                etat.derive?.let { plan -> CarteDerive(plan = plan, onReprendre = onReprendreDerive) }
                // TV or box only; pointless on a phone connected for testing.
                val televiseur = etat.infos.typeAppareil == TypeAppareil.TELEVISEUR || etat.infos.typeAppareil == TypeAppareil.BOX
                CarteAppareil(
                    etat = etat,
                    onDeconnecter = onDeconnecter,
                    onRedemarrer = onRedemarrer,
                    // Refresh also re-reads the TV app, which may have changed on the TV.
                    onActualiser = { onActualiser(); if (televiseur) actionsApplicationTv.onLire() },
                )
                if (televiseur) {
                    CarteApplicationTv(etat = etatApplicationTv, hote = etat.connexion.hote, actions = actionsApplicationTv)
                }
                CarteAccueil(
                    etat = etat,
                    onInstaller = onInstallerLauncher,
                    onDefinirAccueil = onDefinirAccueil,
                    onOuvrirLien = onOuvrirLien,
                )
            },
            droite = {
                CartePermissions(etat = etat.permissions, applications = etatApplications, actions = actionsPermissions)
                CarteInstallation(etat = etat.installation, onChoisir = onChoisirApk)
                CarteCommande(etat = etat.commande, actions = actionsCommande)
            },
        )
        return
    }

    // Discovery only runs while this screen is visible: it stops when the window is minimized and
    // resumes when it is restored.
    LifecycleStartEffect(Unit) {
        onChercher()
        onStopOrDispose { onArreterRecherche() }
    }

    DeuxColonnes(
        gauche = {
            EnTete()
            // Requested reboot: say we are waiting for the TV to come back.
            if (etat.redemarrage) {
                CarteSection(titre = stringResource(Res.string.device_reboot)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(text = stringResource(Res.string.reboot_in_progress), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            CarteDecouverte(etat = etat, onConnecterA = onConnecterA)
            CarteSaisie(etat = etat, onHote = onHote, onPort = onPort, onConnecter = onConnecter)
        },
        droite = {
            CarteAide()
        },
    )
}

@Composable
private fun ColumnScope.EnTete() {
    Text(
        text = stringResource(Res.string.connection_title),
        style = MaterialTheme.typography.titleLarge,
    )
    TexteSecondaire(stringResource(Res.string.connection_subtitle))
}

@Composable
private fun CarteDecouverte(etat: EtatApp, onConnecterA: (AppareilDecouvert) -> Unit) {
    val appareils = etat.detectes
    val enCours = etat.connexion.etat == EtatConnexion.CONNEXION

    CarteSection(titre = stringResource(Res.string.discovery_title)) {
        when {
            appareils.isEmpty() && !etat.decouverte.premierTourTermine -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                TexteSecondaire(stringResource(Res.string.discovery_searching))
            }

            appareils.isEmpty() -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(Res.drawable.baseline_wifi_find_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TexteSecondaire(stringResource(Res.string.discovery_none))
            }

            else -> {
                TexteSecondaire(stringResource(Res.string.discovery_hint), petit = true)
                appareils.forEach { appareil ->
                    BoutonAppareil(
                        appareil = appareil,
                        // Brand of a device seen before: its remembered name starts with it.
                        fabricant = etat.nomsConnus[appareil.hote]?.let { Fabricant.depuisNom(it) },
                        actif = !enCours,
                        onClick = { onConnecterA(appareil) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BoutonAppareil(appareil: AppareilDecouvert, fabricant: Fabricant?, actif: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = actif,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (fabricant != null) {
            PlaqueMarque(fabricant = fabricant, hauteur = 30.dp)
        } else {
            Icon(painter = painterResource(Res.drawable.baseline_connected_tv_24), contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Without a known name the address alone is shown, not twice.
            val adresse = "${appareil.hote}:${appareil.port}"
            Text(
                text = if (appareil.nomConvivial != null) appareil.libelle else adresse,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (appareil.nomConvivial != null) {
                Text(text = adresse, style = MaterialTheme.typography.bodySmall)
            }
            if (appareil.sansFil) {
                Text(
                    text = stringResource(Res.string.discovery_wireless),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CarteSaisie(
    etat: EtatApp,
    onHote: (String) -> Unit,
    onPort: (String) -> Unit,
    onConnecter: () -> Unit,
) {
    val enCours = etat.connexion.etat == EtatConnexion.CONNEXION

    CarteSection(titre = stringResource(Res.string.connection_manual_title), espacement = 12.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = etat.hoteSaisi,
                onValueChange = onHote,
                label = { Text(stringResource(Res.string.connection_host)) },
                placeholder = { Text(stringResource(Res.string.connection_host_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f).surEntree(onConnecter),
            )
            OutlinedTextField(
                value = etat.portSaisi,
                onValueChange = onPort,
                label = { Text(stringResource(Res.string.connection_port)) },
                singleLine = true,
                modifier = Modifier.width(110.dp).surEntree(onConnecter),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onConnecter, enabled = !enCours) {
                Text(stringResource(Res.string.connection_connect))
            }
            if (enCours) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        }
        if (enCours) TexteSecondaire(stringResource(Res.string.connection_waiting))
        EtatErreur(etat.connexion)
    }
}

/** Why the connection failed in plain words, then the technical message. */
@Composable
private fun EtatErreur(connexion: ConnexionUi) {
    if (connexion.etat != EtatConnexion.ERREUR) return
    val explication = when (connexion.probleme ?: ProblemeConnexion.AUTRE) {
        ProblemeConnexion.REFUSEE -> Res.string.connection_problem_refused
        ProblemeConnexion.DELAI -> Res.string.connection_problem_timeout
        ProblemeConnexion.NON_AUTORISEE -> Res.string.connection_problem_unauthorized
        ProblemeConnexion.INJOIGNABLE -> Res.string.connection_problem_unreachable
        ProblemeConnexion.AUTRE -> Res.string.connection_problem_other
    }
    Text(
        text = stringResource(explication),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    if (connexion.detail.isNotBlank()) {
        SelectionContainer { TexteSecondaire(connexion.detail, petit = true) }
    }
}

@Composable
private fun CarteAide() {
    CarteSection(titre = stringResource(Res.string.connection_help_title), espacement = 12.dp) {
        listOf(
            Res.string.connection_help_1,
            Res.string.connection_help_2,
            Res.string.connection_help_3,
            Res.string.connection_help_4,
        ).forEach { etape ->
            Text(text = stringResource(etape), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Enter in a field acts as a click on "Connect". */
private fun Modifier.surEntree(action: () -> Unit): Modifier = onPreviewKeyEvent { evenement ->
    val entree = evenement.key == Key.Enter || evenement.key == Key.NumPadEnter
    if (entree && evenement.type == KeyEventType.KeyDown) {
        action()
        true
    } else {
        false
    }
}
