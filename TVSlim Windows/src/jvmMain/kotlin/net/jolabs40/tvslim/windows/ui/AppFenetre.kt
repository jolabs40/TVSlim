package net.jolabs40.tvslim.windows.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jolabs40.tvslim.windows.InfosApp
import net.jolabs40.tvslim.windows.adb.EtatConnexion
import net.jolabs40.tvslim.windows.maj.PiloteMisesAJour
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.about_title
import net.jolabs40.tvslim.windows.ressources.app_name
import net.jolabs40.tvslim.windows.ressources.baseline_cast_24
import net.jolabs40.tvslim.windows.ressources.baseline_history_24
import net.jolabs40.tvslim.windows.ressources.baseline_info_24
import net.jolabs40.tvslim.windows.ressources.baseline_inventory_2_24
import net.jolabs40.tvslim.windows.ressources.baseline_memory_24
import net.jolabs40.tvslim.windows.ressources.journal_export_dialog
import net.jolabs40.tvslim.windows.ressources.status_connected
import net.jolabs40.tvslim.windows.ressources.status_connecting
import net.jolabs40.tvslim.windows.ressources.status_disconnected
import net.jolabs40.tvslim.windows.ressources.status_error
import net.jolabs40.tvslim.windows.ressources.tab_connection
import net.jolabs40.tvslim.windows.ressources.tab_log
import net.jolabs40.tvslim.windows.ressources.tab_memory
import net.jolabs40.tvslim.windows.ressources.tab_packages
import net.jolabs40.tvslim.windows.ui.ecrans.AProposDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.BanniereMiseAJour
import net.jolabs40.tvslim.windows.ui.ecrans.ConfirmationDialogue
import net.jolabs40.tvslim.windows.ui.ecrans.ConnexionEcran
import net.jolabs40.tvslim.windows.ui.ecrans.JournalEcran
import net.jolabs40.tvslim.windows.ui.ecrans.MemoireEcran
import net.jolabs40.tvslim.windows.ui.ecrans.PaquetsEcran
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/** Les quatre onglets du compagnon, dans le même ordre et avec les mêmes icônes. */
enum class Onglet(val titre: StringResource, val icone: DrawableResource) {
    TELEVISEUR(Res.string.tab_connection, Res.drawable.baseline_cast_24),
    PAQUETS(Res.string.tab_packages, Res.drawable.baseline_inventory_2_24),
    MEMOIRE(Res.string.tab_memory, Res.drawable.baseline_memory_24),
    JOURNAL(Res.string.tab_log, Res.drawable.baseline_history_24),
}

/**
 * La fenêtre : un rail d'onglets à gauche — la barre du bas d'un téléphone, couchée sur le côté —
 * le bandeau de mise à jour en haut, et chaque retour d'action dans la même bannière en bas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFenetre(
    pilote: PiloteApp,
    misesAJour: PiloteMisesAJour,
    onglet: Onglet,
    onOnglet: (Onglet) -> Unit,
    ouvrirLien: (String) -> Unit,
    ouvrirDossierDonnees: () -> Unit,
    choisirFichierExport: (nomPropose: String, titre: String) -> File?,
) {
    val etat by pilote.etat.collectAsStateWithLifecycle()
    val etatMaj by misesAJour.etat.collectAsStateWithLifecycle()
    val messages = remember { SnackbarHostState() }
    var aPropos by remember { mutableStateOf(false) }
    val titreExport = stringResource(Res.string.journal_export_dialog)

    // Une session ADB ne survit pas à la veille du téléviseur. Au retour sur la fenêtre — sortie de
    // la barre des tâches — on retente le dernier téléviseur sans rien demander.
    val proprietaire = LocalLifecycleOwner.current
    DisposableEffect(proprietaire) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_START) pilote.reprendreConnexion()
        }
        proprietaire.lifecycle.addObserver(observateur)
        onDispose { proprietaire.lifecycle.removeObserver(observateur) }
    }

    // Chaque retour d'action passe par la même bannière, puis est consommé.
    LaunchedEffect(etat.message) {
        val message = etat.message ?: return@LaunchedEffect
        val texte = message.rediger()
        messages.showSnackbar(
            message = texte,
            withDismissAction = true,
            duration = if ('\n' in texte) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        pilote.effacerMessage()
    }

    etat.confirmation?.let { demande ->
        ConfirmationDialogue(
            confirmation = demande,
            onConfirmer = pilote::confirmer,
            onAnnuler = pilote::annulerConfirmation,
        )
    }

    if (aPropos) {
        AProposDialogue(
            etat = etatMaj,
            onFermer = { aPropos = false },
            onVerifier = { misesAJour.verifier() },
            onVerificationAuto = misesAJour::majVerificationAuto,
            onInstaller = misesAJour::installer,
            onSource = { ouvrirLien("https://github.com/${InfosApp.DEPOT_GITHUB}") },
            onDossier = ouvrirDossierDonnees,
        )
    }

    val actionsPermissions = remember(pilote) {
        ActionsPermissions(
            onPaquet = pilote.permissions::majPaquet,
            onPermission = pilote.permissions::majPermission,
            onLire = pilote.permissions::lire,
            onAccorder = pilote.permissions::accorder,
            onRetirer = pilote.permissions::retirer,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.app_name)) },
                actions = {
                    PastilleConnexion(etat)
                    IconButton(onClick = { aPropos = true }) {
                        Icon(
                            painter = painterResource(Res.drawable.baseline_info_24),
                            contentDescription = stringResource(Res.string.about_title),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { marges ->
        Row(modifier = Modifier.fillMaxSize().padding(marges)) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(8.dp))
                Onglet.entries.forEach { cible ->
                    NavigationRailItem(
                        selected = cible == onglet,
                        onClick = { onOnglet(cible) },
                        icon = { Icon(painterResource(cible.icone), contentDescription = null) },
                        label = { Text(stringResource(cible.titre)) },
                    )
                }
            }
            VerticalDivider()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                BanniereMiseAJour(
                    etat = etatMaj,
                    onInstaller = misesAJour::installer,
                    onPage = misesAJour::ouvrirPage,
                    onPlusTard = misesAJour::ecarter,
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (onglet) {
                        Onglet.TELEVISEUR -> ConnexionEcran(
                            etat = etat,
                            onHote = pilote::majHote,
                            onPort = pilote::majPort,
                            onConnecter = pilote::connecter,
                            onDeconnecter = pilote::deconnecter,
                            onActualiser = pilote::rafraichir,
                            onInstallerLauncher = pilote::installerLauncher,
                            onChercher = pilote::chercherAppareils,
                            onArreterRecherche = pilote::arreterRecherche,
                            onConnecterA = pilote::connecterA,
                            actionsPermissions = actionsPermissions,
                        )

                        Onglet.PAQUETS -> PaquetsEcran(
                            etat = etat,
                            onBasculer = pilote::basculerSelection,
                            onDetailler = pilote::detailler,
                            onProfil = pilote::selectionnerProfil,
                            onToutDecocher = pilote::toutDeselectionner,
                            onAppliquer = pilote::demanderApplication,
                            onReactiver = { pilote.reactiver(listOf(it)) },
                            onRecherche = pilote::majRecherche,
                            onFiltre = pilote::majFiltre,
                        )

                        Onglet.MEMOIRE -> MemoireEcran(
                            etat = etat,
                            onActualiser = pilote::rafraichirMemoire,
                            onForcerArret = pilote::forcerArret,
                            onRedefinirReference = pilote::redefinirReference,
                        )

                        Onglet.JOURNAL -> JournalEcran(
                            etat = etat,
                            onToutRestaurer = pilote::demanderRestauration,
                            onExporter = {
                                choisirFichierExport(pilote.nomExportJournal(), titreExport)
                                    ?.let(pilote::exporterJournal)
                            },
                            onAnnulerAction = pilote::annulerAction,
                        )
                    }
                }
            }
        }
    }
}

/** Où en est la connexion, lisible de n'importe quel onglet. */
@Composable
private fun PastilleConnexion(etat: EtatApp) {
    val connexion = etat.connexion
    val nom = "${etat.infos.marque} ${etat.infos.modele}".trim()
        .ifBlank { etat.nomsConnus[connexion.hote] ?: connexion.hote }
    val (texte, couleur) = when (connexion.etat) {
        EtatConnexion.CONNECTE ->
            stringResource(Res.string.status_connected, nom) to MaterialTheme.colorScheme.primary

        EtatConnexion.CONNEXION ->
            stringResource(Res.string.status_connecting, connexion.hote) to MaterialTheme.colorScheme.tertiary

        EtatConnexion.ERREUR ->
            stringResource(Res.string.status_error, connexion.hote) to MaterialTheme.colorScheme.error

        EtatConnexion.DECONNECTE ->
            stringResource(Res.string.status_disconnected) to MaterialTheme.colorScheme.outline
    }
    Row(modifier = Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(couleur, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text = texte, style = MaterialTheme.typography.bodyMedium)
    }
}
