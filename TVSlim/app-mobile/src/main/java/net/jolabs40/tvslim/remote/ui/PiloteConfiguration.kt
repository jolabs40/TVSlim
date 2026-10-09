package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import net.jolabs40.tvslim.commande.RelanceShizuku
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
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import java.io.File
import java.io.FileNotFoundException

/**
 * TV configuration: home screen (recommended launcher's store page, watching for its install), saving and
 * re-applying a configuration, exporting packages missing from the catalogue, installing an APK from the phone,
 * and the free-form ADB command.
 *
 * Shares only state and engine with the main view model. Files go through the Android picker, so nothing is
 * read or written unless the user picked it.
 */
class PiloteConfiguration(
    private val contexte: Context,
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val installation: () -> InstallationApk?,
    private val console: ConsoleAdb,
    private val etat: () -> EtatRemote,
    private val majEtat: ((EtatRemote) -> EtatRemote) -> Unit,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
    private val rafraichir: () -> Unit,
    private val terminer: (List<ResultatAction>) -> Unit,
    /** Called after a successful re-apply or install; may show the support banner. */
    private val remercier: () -> Unit,
) {

    /** Watches for a launcher whose store page was just opened. */
    private var guet: Job? = null

    /** Called on disconnect: the watch and the install outcome belong to the previous TV. */
    fun oublier() {
        guet?.cancel()
        guet = null
        majInstallation { EtatInstallation() }
        // Keep the command history; the last output came from the previous TV.
        majCommande { EtatCommande(saisie = it.saisie, historique = it.historique) }
    }

    // --- Home screen ----------------------------------------------------------------------

    /**
     * Opens a launcher's page in the TV's store. The user confirms the install with the remote; the store is
     * the recommended path even when an APK exists.
     */
    fun installerLauncher(paquet: String) {
        // The card offers nothing on non-TV devices; do nothing if reached another way.
        if (!etat().infos.typeAppareil.pourLeCatalogue) return
        val moteurActif = moteur()
        if (moteurActif == null) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        portee.launch {
            val resultat = moteurActif.ouvrirFicheBoutique(paquet)
            if (!resultat.reussi) {
                afficher(contexte.getString(R.string.msg_store_failed, resultat.texte(contexte)))
                return@launch
            }
            afficher(contexte.getString(R.string.msg_store_opened))
            guetterInstallation(paquet)
        }
    }

    /**
     * Makes an installed launcher the TV's home screen. Logged, so it can be undone from the Log tab. The home
     * is read back afterwards because Android answers `Success` without changing anything while a higher-priority
     * factory home is still enabled (Google TV on the TCL).
     */
    fun definirAccueil(composant: String) {
        // The card offers nothing on non-TV devices; do nothing if reached another way.
        if (!etat().infos.typeAppareil.pourLeCatalogue) return
        val moteurActif = moteur()
        if (moteurActif == null) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        val infos = etat().infos
        val paquet = composant.substringBefore('/')
        val nom = etat().catalogue.nomLauncher(paquet) ?: paquet
        portee.launch {
            val resultat = moteurActif.definirAccueil(composant, infos.composantAccueil.ifBlank { composant })
            if (!resultat.reussi) {
                afficher(contexte.getString(R.string.msg_home_failed, resultat.texte(contexte).ifBlank { "—" }))
                return@launch
            }
            val enPlace = lecteur.accueilActuel()
            rafraichir()
            afficher(
                if (enPlace == paquet) {
                    contexte.getString(R.string.msg_home_set, nom)
                } else {
                    contexte.getString(R.string.msg_home_kept, etat().catalogue.nomLauncher(enPlace) ?: enPlace)
                },
            )
        }
    }

    /**
     * Polls for the launcher after opening its store page, since the user is at the TV rather than the phone.
     * Every five seconds, for at most three minutes.
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
                        afficher(contexte.getString(R.string.msg_launcher_installed))
                        return@withTimeoutOrNull
                    }
                }
            }
        }
    }

    // --- Save and re-apply ----------------------------------------------------------------

    /** Suggested file name: device and date. */
    fun nomFichier(): String = FichierConfiguration.nomPropose(etat().infos)

    /** Writes the TV's configuration, as last read, to the picked file. */
    fun sauvegarder(cible: Uri) {
        val courant = etat()
        if (!courant.connecte || courant.lignes.isEmpty()) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        val texte = FichierConfiguration.ecrire(courant.catalogue.configurationDe(courant.infos, courant.etats()))
        portee.launch {
            runCatching { ecrire(cible, texte) }
                .onSuccess { afficher(contexte.getString(R.string.msg_config_saved)) }
                .onFailure { afficher(contexte.getString(R.string.msg_config_save_failed, it.message.orEmpty())) }
        }
    }

    /**
     * Reads a saved configuration and compares it with the TV. Nothing is sent: changes go to confirmation, and
     * a TV that already matches just shows a message.
     */
    fun charger(source: Uri) {
        if (!etat().connecte || moteur() == null) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        portee.launch {
            val lue = runCatching {
                withContext(Dispatchers.IO) {
                    val flux = contexte.contentResolver.openInputStream(source)
                        ?: throw FileNotFoundException(source.toString())
                    flux.use { it.readBytes().decodeToString() }
                }
            }
            val configuration = lue.getOrNull()?.let(FichierConfiguration::lire)
            val courant = etat()
            when {
                lue.isFailure -> afficher(
                    contexte.getString(R.string.msg_config_read_failed, lue.exceptionOrNull()?.message.orEmpty()),
                )

                configuration == null -> afficher(contexte.getString(R.string.msg_config_invalid))
                else -> proposer(configuration.planifier(courant.catalogue, courant.etats(), courant.infos))
            }
        }
    }

    private fun proposer(plan: PlanReinjection) {
        val accueilAbsent = plan.accueil
        when {
            !plan.rienAFaire -> majEtat { it.copy(confirmation = Confirmation.Reinjection(plan)) }
            accueilAbsent != null ->
                afficher(contexte.getString(R.string.msg_config_home_missing, accueilAbsent.nom))

            else -> afficher(contexte.getString(R.string.msg_config_up_to_date))
        }
    }

    /**
     * Offers to undo drift. Same confirmation, [Reinjecteur] and safeguards as a re-apply; only the plan's
     * origin differs.
     */
    fun proposerDerive() {
        val plan = etat().derive ?: return
        majEtat { it.copy(confirmation = Confirmation.Reinjection(plan, derive = true)) }
    }

    /** Re-applies after confirmation, with progress and a summary like a batch apply. */
    fun reinjecter(plan: PlanReinjection) {
        val courant = etat()
        val moteurActif = moteur()
        if (moteurActif == null) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
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

    // --- Unknown packages report ---------------------------------------------------------

    /** Suggested file name: device and date. */
    fun nomExportInconnus(): String = RapportInconnus.nomPropose(etat().infos)

    /**
     * Writes a report of packages missing from the catalogue with what ADB says about each (location, privileges,
     * sensitive declarations, icon, RAM and storage), then the firmware and the catalogue entries the device has.
     * Everything is re-read at export time in four reads; nothing is written to the TV.
     *
     * With [proposer], the catalogue submission form then opens in the phone's browser; the user attaches the file.
     */
    fun exporterInconnus(cible: Uri, proposer: Boolean = false) {
        if (!etat().connecte) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        portee.launch {
            afficher(contexte.getString(R.string.msg_unknown_reading))
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
                    application = "${contexte.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
                    releve = releve,
                    duCatalogue = courant.lignes.associate { it.entree to it.etat },
                )
                ecrire(cible, rapport)
            }
                .onSuccess {
                    afficher(if (proposer) ouvrirProposition() else contexte.getString(R.string.msg_unknown_exported))
                }
                .onFailure { afficher(contexte.getString(R.string.msg_unknown_export_failed, it.message.orEmpty())) }
        }
    }

    /** Opens the prefilled catalogue form and returns the message telling what to do with the file. */
    private fun ouvrirProposition(): String {
        val lien = PropositionCatalogue.lien(etat().infos)
        val vue = Intent(Intent.ACTION_VIEW, Uri.parse(lien)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { contexte.startActivity(vue) }.isSuccess) {
            contexte.getString(R.string.msg_unknown_proposed)
        } else {
            contexte.getString(R.string.msg_unknown_no_browser, lien)
        }
    }

    // --- APK install ---------------------------------------------------------------------

    /**
     * Examines an APK picked in the Android picker. It is first copied to the cache, since both dadb and the
     * manifest reader need a file. Nothing reaches the TV before confirmation.
     */
    fun choisirApk(source: Uri) {
        val installationActive = installation() ?: return afficher(contexte.getString(R.string.msg_connect_first))
        if (etat().installation.occupee) return afficher(contexte.getString(R.string.msg_apk_busy))
        portee.launch {
            majInstallation { it.copy(phase = PhaseInstallation.Examen) }
            val nom = nomAffiche(source)
            val copie = runCatching { copier(source) }.getOrElse { erreur ->
                majInstallation { it.copy(phase = null) }
                afficher(contexte.getString(R.string.msg_config_read_failed, erreur.message.orEmpty()))
                return@launch
            }
            val examen = installationActive.examiner(copie, nom)
            majInstallation { it.copy(phase = null) }
            when (examen) {
                is ExamenApk.Pret -> majEtat { it.copy(confirmation = Confirmation.Installation(examen.apk)) }
                is ExamenApk.Refuse -> {
                    withContext(Dispatchers.IO) { copie.delete() }
                    afficher(messageRefus(examen))
                }
            }
        }
    }

    /** Uploads and installs after confirmation; progress follows the upload, then Android's install. */
    fun installerApk(apk: ApkChoisi) {
        val installationActive = installation() ?: return afficher(contexte.getString(R.string.msg_connect_first))
        portee.launch {
            majInstallation { EtatInstallation(phase = PhaseInstallation.Envoi(0, apk.taille)) }
            val resultat = installationActive.installer(apk) { envoye, total ->
                val phase = if (envoye >= total) PhaseInstallation.Installation else PhaseInstallation.Envoi(envoye, total)
                majInstallation { it.copy(phase = phase) }
            }
            withContext(Dispatchers.IO) { apk.fichier.delete() }
            majInstallation { EtatInstallation(derniere = resultat) }
            afficher(
                when (resultat) {
                    is ResultatInstallation.Reussie ->
                        contexte.getString(R.string.msg_apk_installed, apk.manifeste.paquet)

                    is ResultatInstallation.Echouee ->
                        contexte.getString(R.string.msg_apk_failed, contexte.getString(resultat.cause.ressource()))
                },
            )
            if (InvitationSoutien.merite(resultat)) remercier()
            // Package counts changed, and the new app may be a launcher.
            rafraichir()
        }
    }

    /** Confirmation declined: deletes the cached copy. */
    fun abandonnerApk(apk: ApkChoisi) {
        portee.launch(Dispatchers.IO) { apk.fichier.delete() }
    }

    private fun messageRefus(examen: ExamenApk.Refuse): String = when (examen.refus) {
        RefusApk.PAS_UN_APK -> contexte.getString(R.string.msg_apk_not_apk)
        RefusApk.LOT -> contexte.getString(R.string.msg_apk_bundle)
        RefusApk.PAQUET_INVALIDE -> contexte.getString(R.string.msg_apk_invalid_package)
        RefusApk.ANDROID_TROP_ANCIEN ->
            contexte.getString(R.string.msg_apk_sdk, examen.minSdk ?: 0, examen.sdkTeleviseur ?: 0)

        RefusApk.TELEVISEUR_INJOIGNABLE -> contexte.getString(R.string.msg_apk_unreachable)
    }

    /** Display name of the picked file; for display only, never used in a path. */
    private suspend fun nomAffiche(source: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            contexte.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { curseur -> if (curseur.moveToFirst()) curseur.getString(0) else null }
        }.getOrNull() ?: source.lastPathSegment ?: NOM_COPIE
    }

    /**
     * Copies the APK to the cache under a fixed name, since the display name comes from another app. Clearing
     * the folder also removes any copy left by an abandoned attempt.
     */
    private suspend fun copier(source: Uri): File = withContext(Dispatchers.IO) {
        val dossier = File(contexte.cacheDir, DOSSIER_APK).apply {
            deleteRecursively()
            mkdirs()
        }
        val copie = File(dossier, NOM_COPIE)
        val flux = contexte.contentResolver.openInputStream(source) ?: throw FileNotFoundException(source.toString())
        flux.use { entree -> copie.outputStream().use { sortie -> entree.copyTo(sortie) } }
        copie
    }

    private fun majInstallation(transformation: (EtatInstallation) -> EtatInstallation) =
        majEtat { it.copy(installation = transformation(it.installation)) }

    // --- Free-form ADB command ------------------------------------------------------------

    fun saisirCommande(valeur: String) = majCommande { it.copy(saisie = valeur) }

    /**
     * Sends the typed command once and keeps its output on screen. No confirmation and no safeguard (the card
     * says so); every command is logged.
     */
    fun envoyerCommande() {
        val courant = etat()
        if (!courant.connecte) return afficher(contexte.getString(R.string.msg_connect_first))
        if (courant.commande.enCours) return
        when (val saisie = ConsoleAdb.lire(courant.commande.saisie)) {
            is SaisieCommande.Refusee -> afficher(
                contexte.getString(
                    when (saisie.refus) {
                        RefusCommande.VIDE -> R.string.msg_command_empty
                        RefusCommande.PAS_SHELL -> R.string.msg_command_not_shell
                        RefusCommande.TROP_LONGUE -> R.string.msg_command_too_long
                    },
                ),
            )

            is SaisieCommande.Prete -> portee.launch {
                majCommande { it.copy(enCours = true) }
                val echange = console.envoyer(saisie.commande)
                majCommande { it.avecEnvoi(saisie.commande).copy(enCours = false, derniere = echange) }
                // The command may have changed what other tabs show.
                rafraichir()
            }
        }
    }

    private fun majCommande(transformation: (EtatCommande) -> EtatCommande) =
        majEtat { it.copy(commande = transformation(it.commande)) }

    // --- Shizuku restart -------------------------------------------------------------------

    /**
     * Restarts the TV's Shizuku service (see [RelanceShizuku]).
     *
     * Unlike the free-form command, the command is a core constant, never user input. It still goes through the
     * console, so it is logged and sent once, never replayed after a broken session. Other tabs are not
     * refreshed: starting a service changes neither packages, home nor memory.
     */
    fun relancerShizuku() {
        val courant = etat()
        if (!courant.connecte) return afficher(contexte.getString(R.string.msg_connect_first))
        if (courant.shizuku.enCours) return
        portee.launch {
            majShizuku { it.copy(enCours = true) }
            val echange = console.envoyer(RelanceShizuku.COMMANDE)
            majShizuku { EtatShizuku(enCours = false, derniere = echange) }
        }
    }

    private fun majShizuku(transformation: (EtatShizuku) -> EtatShizuku) =
        majEtat { it.copy(shizuku = transformation(it.shizuku)) }

    /** Mode "wt" truncates, so shorter content does not leave the old file's tail behind. */
    private suspend fun ecrire(cible: Uri, texte: String) = withContext(Dispatchers.IO) {
        val flux = contexte.contentResolver.openOutputStream(cible, "wt")
            ?: throw FileNotFoundException(cible.toString())
        flux.use { it.write(texte.toByteArray()) }
    }

    private fun EtatRemote.etats() = lignes.associate { it.entree.paquet to it.etat }

    private companion object {
        const val DUREE_GUET_MS = 3 * 60 * 1000L
        const val INTERVALLE_GUET_MS = 5_000L
        const val DOSSIER_APK = "apk"
        const val NOM_COPIE = "application.apk"
    }
}
