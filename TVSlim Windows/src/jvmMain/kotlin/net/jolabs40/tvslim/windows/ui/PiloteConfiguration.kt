package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.commande.ConsoleAdb
import net.jolabs40.tvslim.commande.RefusCommande
import net.jolabs40.tvslim.commande.SaisieCommande
import net.jolabs40.tvslim.configuration.FichierConfiguration
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.configuration.Reinjecteur
import net.jolabs40.tvslim.configuration.configurationDe
import net.jolabs40.tvslim.configuration.planifier
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.device.PropositionCatalogue
import net.jolabs40.tvslim.device.RapportInconnus
import net.jolabs40.tvslim.device.ReleveInconnus
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.installation.ExamenApk
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.installation.RefusApk
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.soutien.InvitationSoutien
import net.jolabs40.tvslim.windows.InfosApp
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.msg_apk_bundle
import net.jolabs40.tvslim.windows.ressources.msg_apk_busy
import net.jolabs40.tvslim.windows.ressources.msg_apk_failed
import net.jolabs40.tvslim.windows.ressources.msg_apk_installed
import net.jolabs40.tvslim.windows.ressources.msg_apk_invalid_package
import net.jolabs40.tvslim.windows.ressources.msg_apk_not_apk
import net.jolabs40.tvslim.windows.ressources.msg_apk_sdk
import net.jolabs40.tvslim.windows.ressources.msg_apk_unreachable
import net.jolabs40.tvslim.windows.ressources.msg_command_empty
import net.jolabs40.tvslim.windows.ressources.msg_command_not_shell
import net.jolabs40.tvslim.windows.ressources.msg_command_too_long
import net.jolabs40.tvslim.windows.ressources.msg_config_home_missing
import net.jolabs40.tvslim.windows.ressources.msg_config_invalid
import net.jolabs40.tvslim.windows.ressources.msg_config_read_failed
import net.jolabs40.tvslim.windows.ressources.msg_config_save_failed
import net.jolabs40.tvslim.windows.ressources.msg_config_saved
import net.jolabs40.tvslim.windows.ressources.msg_config_up_to_date
import net.jolabs40.tvslim.windows.ressources.msg_connect_first
import net.jolabs40.tvslim.windows.ressources.msg_home_failed
import net.jolabs40.tvslim.windows.ressources.msg_home_kept
import net.jolabs40.tvslim.windows.ressources.msg_home_set
import net.jolabs40.tvslim.windows.ressources.msg_launcher_installed
import net.jolabs40.tvslim.windows.ressources.msg_store_failed
import net.jolabs40.tvslim.windows.ressources.msg_store_opened
import net.jolabs40.tvslim.windows.ressources.msg_unknown_export_failed
import net.jolabs40.tvslim.windows.ressources.msg_unknown_exported
import net.jolabs40.tvslim.windows.ressources.msg_unknown_proposed
import net.jolabs40.tvslim.windows.ressources.msg_unknown_reading
import java.io.File

/**
 * TV configuration: home screen (recommended launcher, install watch), saved configurations to reapply later
 * (launcher and packages), the unknown packages report, APK install and the free ADB command.
 *
 * Split from the main controller like permissions: it shares only state and engine, and reapplying goes
 * through the same safeguards as a batch disable.
 */
class PiloteConfiguration(
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val installation: () -> InstallationApk?,
    private val console: ConsoleAdb,
    private val etat: () -> EtatApp,
    private val majEtat: ((EtatApp) -> EtatApp) -> Unit,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    private val rafraichir: () -> Unit,
    private val terminer: (List<ResultatAction>) -> Unit,
    /** A reapply or install succeeded: the support banner may show. */
    private val remercier: () -> Unit,
) {

    /** Watches for a launcher whose install was just started. */
    private var guet: Job? = null

    /** Called on disconnect: the install watch and result only applied to the previous TV. */
    fun oublier() {
        guet?.cancel()
        guet = null
        majInstallation { EtatInstallation() }
        // Typed commands stay within reach of Up; the output belonged to the previous TV.
        majCommande { EtatCommande(saisie = it.saisie, historique = it.historique) }
    }

    // --- Home screen ----------------------------------------------------------------------

    /**
     * Opens a launcher's page in the TV's store. The user confirms the install with the remote; the store is
     * the recommended path even where an APK exists.
     */
    fun installerLauncher(paquet: String) {
        // The card offers nothing on non-TV devices; do nothing either if called some other way.
        if (!etat().infos.typeAppareil.pourLeCatalogue) return
        val moteurActif = moteur() ?: return afficher(texte(Res.string.msg_connect_first))
        portee.launch {
            val resultat = moteurActif.ouvrirFicheBoutique(paquet)
            if (!resultat.reussi) {
                afficher(texte(Res.string.msg_store_failed, resultat.texte()))
                return@launch
            }
            afficher(texte(Res.string.msg_store_opened))
            guetterInstallation(paquet)
        }
    }

    /**
     * Makes an installed launcher the TV's home screen. Logged, so it can be undone from the Log tab. Re-read
     * afterwards: Android answers `Success` without changing anything while a higher-priority factory home is
     * still enabled (Google TV on the TCL).
     */
    fun definirAccueil(composant: String) {
        // The card offers nothing on non-TV devices; do nothing either if called some other way.
        if (!etat().infos.typeAppareil.pourLeCatalogue) return
        val moteurActif = moteur() ?: return afficher(texte(Res.string.msg_connect_first))
        val infos = etat().infos
        val paquet = composant.substringBefore('/')
        val nom = etat().catalogue.nomLauncher(paquet) ?: paquet
        portee.launch {
            val resultat = moteurActif.definirAccueil(composant, infos.composantAccueil.ifBlank { composant })
            if (!resultat.reussi) {
                afficher(texte(Res.string.msg_home_failed, if (resultat.motif == null && resultat.message.isBlank()) MessageUi.Brut("—") else resultat.texte()))
                return@launch
            }
            val enPlace = lecteur.accueilActuel()
            rafraichir()
            afficher(
                if (enPlace == paquet) {
                    texte(Res.string.msg_home_set, nom)
                } else {
                    texte(Res.string.msg_home_kept, etat().catalogue.nomLauncher(enPlace) ?: enPlace)
                },
            )
        }
    }

    /**
     * Polls for the launcher instead of requiring a Refresh, since the user is at the TV, not the PC. One short
     * query every five seconds, for three minutes.
     */
    private fun guetterInstallation(paquet: String) {
        guet?.cancel()
        guet = portee.launch {
            withTimeoutOrNull(DUREE_GUET_MS) {
                while (isActive) {
                    delay(INTERVALLE_GUET_MS)
                    if (!etat().connecte) return@withTimeoutOrNull
                    if (lecteur.estInstalle(paquet)) {
                        rafraichir()
                        afficher(texte(Res.string.msg_launcher_installed))
                        return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    // --- Save and reapply -----------------------------------------------------------------

    /** Name suggested by the save dialog: device and date. */
    fun nomFichier(): String = FichierConfiguration.nomPropose(etat().infos)

    /** Writes the TV configuration as last read. */
    fun sauvegarder(cible: File) {
        val courant = etat()
        if (!courant.connecte || courant.lignes.isEmpty()) return afficher(texte(Res.string.msg_connect_first))
        val configuration = courant.catalogue.configurationDe(courant.infos, courant.etats())
        portee.launch {
            runCatching { withContext(Dispatchers.IO) { cible.writeText(FichierConfiguration.ecrire(configuration)) } }
                .onSuccess { afficher(texte(Res.string.msg_config_saved, cible.path)) }
                .onFailure { afficher(texte(Res.string.msg_config_save_failed, it.message.orEmpty())) }
        }
    }

    /**
     * Reads a saved configuration and compares it with the TV. Nothing is sent: changes go to confirmation, and
     * a TV that already matches is reported without a dialog.
     */
    fun charger(source: File) {
        if (!etat().connecte || moteur() == null) return afficher(texte(Res.string.msg_connect_first))
        portee.launch {
            val lue = runCatching { withContext(Dispatchers.IO) { source.readText() } }
            val configuration = lue.getOrNull()?.let(FichierConfiguration::lire)
            val courant = etat()
            when {
                lue.isFailure ->
                    afficher(texte(Res.string.msg_config_read_failed, lue.exceptionOrNull()?.message.orEmpty()))

                configuration == null -> afficher(texte(Res.string.msg_config_invalid))
                else -> proposer(configuration.planifier(courant.catalogue, courant.etats(), courant.infos))
            }
        }
    }

    private fun proposer(plan: PlanReinjection) {
        val accueilAbsent = plan.accueil
        when {
            !plan.rienAFaire -> majEtat { it.copy(confirmation = Confirmation.Reinjection(plan)) }
            accueilAbsent != null -> afficher(texte(Res.string.msg_config_home_missing, accueilAbsent.nom))
            else -> afficher(texte(Res.string.msg_config_up_to_date))
        }
    }

    /**
     * Offers to restore what drifted. Same confirmation, [Reinjecteur] and safeguards as a reapply; only the
     * plan's source differs.
     */
    fun proposerDerive() {
        val plan = etat().derive ?: return
        majEtat { it.copy(confirmation = Confirmation.Reinjection(plan, derive = true)) }
    }

    /** Reapplies after confirmation, with the progress and summary of a batch. */
    fun reinjecter(plan: PlanReinjection) {
        val courant = etat()
        val moteurActif = moteur() ?: return afficher(texte(Res.string.msg_connect_first))
        portee.launch {
            majEtat { it.copy(progression = Progression(0, plan.nombreActions)) }
            val resultats = Reinjecteur(moteurActif).reinjecter(
                plan = plan,
                catalogue = courant.catalogue,
                etats = courant.etats(),
                infos = courant.infos,
                surProgression = { fait, total -> majEtat { it.copy(progression = Progression(fait, total)) } },
            )
            terminer(resultats)
            if (InvitationSoutien.merite(resultats)) remercier()
        }
    }

    // --- Unknown packages report ----------------------------------------------------------

    /** Suggested report name: device and date. */
    fun nomExportInconnus(): String = RapportInconnus.nomPropose(etat().infos)

    /**
     * Writes a report of packages missing from the catalogue, with what ADB says about each (location, flags,
     * sensitive declarations, icon, RAM and storage), then the firmware and the catalogue entries the device
     * has. Everything is re-read at export time in four reads; nothing is written to the TV.
     *
     * With [puisOuvrir], the catalogue form then opens in the browser for the user to attach the file and send it.
     */
    fun exporterInconnus(cible: File, puisOuvrir: ((String) -> Unit)? = null) {
        if (!etat().connecte) return afficher(texte(Res.string.msg_connect_first))
        portee.launch {
            afficher(texte(Res.string.msg_unknown_reading))
            runCatching {
                val releve = ReleveInconnus(
                    indices = lecteur.indices(),
                    memoire = lecteur.memoire(),
                    stockage = lecteur.stockage(),
                    firmware = lecteur.firmware(),
                )
                val courant = etat()
                val rapport = RapportInconnus.markdown(
                    infos = courant.infos,
                    inconnus = courant.inconnus,
                    application = "TV Slim pour Windows ${InfosApp.VERSION}",
                    releve = releve,
                    duCatalogue = courant.lignes.associate { it.entree to it.etat },
                )
                withContext(Dispatchers.IO) { cible.writeText(rapport) }
            }
                .onSuccess {
                    if (puisOuvrir == null) {
                        afficher(texte(Res.string.msg_unknown_exported, cible.path))
                    } else {
                        puisOuvrir(PropositionCatalogue.lien(etat().infos, InfosApp.DEPOT_GITHUB))
                        afficher(texte(Res.string.msg_unknown_proposed, cible.path))
                    }
                }
                .onFailure { afficher(texte(Res.string.msg_unknown_export_failed, it.message.orEmpty())) }
        }
    }

    // --- APK install ----------------------------------------------------------------------

    /**
     * Examines an APK picked or dropped on the window, and what the TV already has. Nothing is sent before
     * confirmation, which shows the package, its version and what it replaces.
     */
    fun choisirApk(fichier: File) {
        val installationActive = installation() ?: return afficher(texte(Res.string.msg_connect_first))
        if (etat().installation.occupee) return afficher(texte(Res.string.msg_apk_busy))
        portee.launch {
            majInstallation { it.copy(phase = PhaseInstallation.Examen) }
            val examen = installationActive.examiner(fichier, fichier.name)
            majInstallation { it.copy(phase = null) }
            when (examen) {
                is ExamenApk.Pret -> majEtat { it.copy(confirmation = Confirmation.Installation(examen.apk)) }
                is ExamenApk.Refuse -> afficher(messageRefus(examen))
            }
        }
    }

    /** Uploads then installs after confirmation; progress follows the upload, then Android's install. */
    fun installerApk(apk: ApkChoisi) {
        val installationActive = installation() ?: return afficher(texte(Res.string.msg_connect_first))
        portee.launch {
            majInstallation { EtatInstallation(phase = PhaseInstallation.Envoi(0, apk.taille)) }
            val resultat = installationActive.installer(apk) { envoye, total ->
                val phase = if (envoye >= total) PhaseInstallation.Installation else PhaseInstallation.Envoi(envoye, total)
                majInstallation { it.copy(phase = phase) }
            }
            majInstallation { EtatInstallation(derniere = resultat) }
            afficher(
                when (resultat) {
                    is ResultatInstallation.Reussie -> texte(Res.string.msg_apk_installed, apk.manifeste.paquet)
                    is ResultatInstallation.Echouee -> texte(Res.string.msg_apk_failed, texte(resultat.cause.ressource()))
                },
            )
            if (InvitationSoutien.merite(resultat)) remercier()
            // Package counts changed, and the new app may be a launcher.
            rafraichir()
        }
    }

    private fun messageRefus(examen: ExamenApk.Refuse): MessageUi = when (examen.refus) {
        RefusApk.PAS_UN_APK -> texte(Res.string.msg_apk_not_apk)
        RefusApk.LOT -> texte(Res.string.msg_apk_bundle)
        RefusApk.PAQUET_INVALIDE -> texte(Res.string.msg_apk_invalid_package)
        RefusApk.ANDROID_TROP_ANCIEN -> texte(Res.string.msg_apk_sdk, examen.minSdk ?: 0, examen.sdkTeleviseur ?: 0)
        RefusApk.TELEVISEUR_INJOIGNABLE -> texte(Res.string.msg_apk_unreachable)
    }

    private fun majInstallation(transformation: (EtatInstallation) -> EtatInstallation) =
        majEtat { it.copy(installation = transformation(it.installation)) }

    // --- Free ADB command -----------------------------------------------------------------

    fun saisirCommande(valeur: String) = majCommande { it.copy(saisie = valeur, rappel = -1) }

    /** Up and Down in the field, as in a terminal. */
    fun rappelerCommande(plusAncienne: Boolean) = majCommande { it.avecRappel(plusAncienne) }

    /**
     * Sends the typed command once and keeps its output on screen. No confirmation or safeguard: the card
     * warns about it, and every send is logged.
     */
    fun envoyerCommande() {
        val courant = etat()
        if (!courant.connecte) return afficher(texte(Res.string.msg_connect_first))
        if (courant.commande.enCours) return
        when (val saisie = ConsoleAdb.lire(courant.commande.saisie)) {
            is SaisieCommande.Refusee -> afficher(
                texte(
                    when (saisie.refus) {
                        RefusCommande.VIDE -> Res.string.msg_command_empty
                        RefusCommande.PAS_SHELL -> Res.string.msg_command_not_shell
                        RefusCommande.TROP_LONGUE -> Res.string.msg_command_too_long
                    },
                ),
            )

            is SaisieCommande.Prete -> portee.launch {
                majCommande { it.copy(enCours = true) }
                val echange = console.envoyer(saisie.commande)
                majCommande { it.avecEnvoi(saisie.commande).copy(enCours = false, derniere = echange) }
                // It may have changed what other tabs show: packages, home screen, counters.
                rafraichir()
            }
        }
    }

    private fun majCommande(transformation: (EtatCommande) -> EtatCommande) =
        majEtat { it.copy(commande = transformation(it.commande)) }

    private fun EtatApp.etats() = lignes.associate { it.entree.paquet to it.etat }

    private companion object {
        const val DUREE_GUET_MS = 3 * 60 * 1000L
        const val INTERVALLE_GUET_MS = 5_000L
    }
}
