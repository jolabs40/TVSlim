package net.jolabs40.tvslim.windows.ui.ecrans

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
import net.jolabs40.tvslim.windows.ui.ActionsCommande
import net.jolabs40.tvslim.windows.ui.ActionsPermissions
import net.jolabs40.tvslim.windows.ui.EtatApp
import net.jolabs40.tvslim.windows.ui.composants.CarteSection
import net.jolabs40.tvslim.windows.ui.composants.DeuxColonnes
import net.jolabs40.tvslim.windows.ui.composants.PlaqueMarque
import net.jolabs40.tvslim.windows.ui.composants.TexteSecondaire
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * L'onglet Téléviseur : trouver l'appareil et s'y connecter, puis — une fois joint — son état, son
 * écran d'accueil, les permissions privilégiées et l'installation d'un APK. Le contenu du compagnon, en
 * deux colonnes.
 *
 * Pas de scanner de code ici : rien n'oblige à installer l'application sur le téléviseur. La
 * recherche sur le réseau et la saisie de l'adresse suffisent.
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
    onChercher: () -> Unit,
    onArreterRecherche: () -> Unit,
    onConnecterA: (AppareilDecouvert) -> Unit,
    actionsPermissions: ActionsPermissions,
    onChoisirApk: () -> Unit,
    actionsCommande: ActionsCommande,
) {
    if (etat.connecte) {
        DeuxColonnes(
            gauche = {
                // Le téléphone met l'action en tête pour éviter de défiler. Sur un bureau, tout
                // tient à l'écran : l'appareil joint d'abord, son écran d'accueil ensuite. Le titre
                // « Se connecter » n'a plus lieu d'être, la barre du haut dit à qui l'on parle.
                CarteAppareil(etat = etat, onDeconnecter = onDeconnecter, onActualiser = onActualiser)
                CarteAccueil(etat = etat, onInstaller = onInstallerLauncher)
            },
            droite = {
                CartePermissions(etat = etat.permissions, actions = actionsPermissions)
                CarteInstallation(etat = etat.installation, onChoisir = onChoisirApk)
                CarteCommande(etat = etat.commande, actions = actionsCommande)
            },
        )
        return
    }

    // La recherche ne tourne que pendant qu'on regarde cet écran, fenêtre visible : réduite dans la
    // barre des tâches, elle s'arrête, et reprend au retour.
    LifecycleStartEffect(Unit) {
        onChercher()
        onStopOrDispose { onArreterRecherche() }
    }

    DeuxColonnes(
        gauche = {
            EnTete()
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
                        // La marque d'un appareil déjà joint : le nom qu'on en a retenu commence par elle.
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
            // Sans nom connu, l'adresse suffit : l'écrire deux fois n'apprend rien.
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

/** Pourquoi la connexion a échoué, en clair, puis le message technique pour qui le veut. */
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

/** Entrée dans un champ vaut un clic sur « Se connecter ». */
private fun Modifier.surEntree(action: () -> Unit): Modifier = onPreviewKeyEvent { evenement ->
    val entree = evenement.key == Key.Enter || evenement.key == Key.NumPadEnter
    if (entree && evenement.type == KeyEventType.KeyDown) {
        action()
        true
    } else {
        false
    }
}
