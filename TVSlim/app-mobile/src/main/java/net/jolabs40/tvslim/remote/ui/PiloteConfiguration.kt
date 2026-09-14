package net.jolabs40.tvslim.remote.ui

import android.content.Context
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
import net.jolabs40.tvslim.commande.SaisieCommande
import net.jolabs40.tvslim.configuration.FichierConfiguration
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.configuration.Reinjecteur
import net.jolabs40.tvslim.configuration.configurationDe
import net.jolabs40.tvslim.configuration.planifier
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.device.RapportInconnus
import net.jolabs40.tvslim.device.ReleveInconnus
import net.jolabs40.tvslim.installation.ApkChoisi
import net.jolabs40.tvslim.installation.ExamenApk
import net.jolabs40.tvslim.installation.InstallationApk
import net.jolabs40.tvslim.installation.RefusApk
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.remote.BuildConfig
import net.jolabs40.tvslim.remote.R
import java.io.File
import java.io.FileNotFoundException

/**
 * La configuration du téléviseur : son écran d'accueil — la fiche du launcher recommandé, le guet de
 * son installation — et la sauvegarde qu'on réinjecte plus tard, launcher et paquets ensemble. S'y ajoutent
 * l'inventaire de ce que le catalogue ignore, relevé et exporté à la demande, l'installation d'un APK
 * qu'on a sur le téléphone, et la commande ADB libre.
 *
 * Tirée du pilote principal comme les permissions : elle n'en partage que l'état et le moteur. Les
 * fichiers passent par le sélecteur d'Android : rien n'est écrit ni lu sans qu'on l'ait désigné.
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
) {

    /** Guette l'arrivée d'un launcher que l'on vient d'envoyer installer. */
    private var guet: Job? = null

    /** Appelé à la déconnexion : le guet et le bilan d'installation ne valent que pour le téléviseur quitté. */
    fun oublier() {
        guet?.cancel()
        guet = null
        majInstallation { EtatInstallation() }
        // Les commandes déjà tapées restent dans le menu ; la sortie, elle, était celle du téléviseur quitté.
        majCommande { EtatCommande(saisie = it.saisie, historique = it.historique) }
    }

    // --- Écran d'accueil ------------------------------------------------------------------

    /**
     * Ouvre la fiche d'un launcher dans la boutique du téléviseur. L'installation elle-même se
     * valide à la télécommande, et vient de la boutique : c'est le chemin recommandé, là où un APK
     * existe aussi.
     */
    fun installerLauncher(paquet: String) {
        val moteurActif = moteur()
        if (moteurActif == null) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        portee.launch {
            val resultat = moteurActif.ouvrirFicheBoutique(paquet)
            if (!resultat.reussi) {
                afficher(contexte.getString(R.string.msg_store_failed, resultat.message))
                return@launch
            }
            afficher(contexte.getString(R.string.msg_store_opened))
            guetterInstallation(paquet)
        }
    }

    /**
     * Guette l'arrivée du launcher après avoir ouvert sa fiche, plutôt que d'exiger un
     * « Actualiser » manuel : la personne est devant son téléviseur, pas devant le téléphone.
     * Une question courte toutes les cinq secondes, abandonnée au bout de trois minutes.
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

    // --- Sauvegarde et réinjection --------------------------------------------------------

    /** Nom proposé par le sélecteur d'Android : l'appareil et le jour. */
    fun nomFichier(): String = FichierConfiguration.nomPropose(etat().infos)

    /** Écrit la configuration du téléviseur tel qu'il a été lu en dernier, là où on l'a choisi. */
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
     * Relit une sauvegarde et la compare au téléviseur. Rien ne part : ce qui changerait est soumis à
     * confirmation, et un téléviseur déjà conforme le dit sans ouvrir de fenêtre.
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

    /** Réinjecte après confirmation, avec la progression et le bilan d'une application en lot. */
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
        }
    }

    // --- Inventaire des inconnus ----------------------------------------------------------

    /** Nom proposé pour l'inventaire : l'appareil et le jour. */
    fun nomExportInconnus(): String = RapportInconnus.nomPropose(etat().infos)

    /**
     * Écrit l'inventaire des paquets que le catalogue ignore, avec ce qu'ADB dit de chacun : emplacement,
     * droits, déclarations sensibles, icône, mémoire vive et stockage ; puis le firmware de l'appareil et les
     * entrées du catalogue qu'il porte déjà. Tout est relu au moment de l'export, par quatre lectures ; rien
     * n'est écrit sur le téléviseur.
     */
    fun exporterInconnus(cible: Uri) {
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
                .onSuccess { afficher(contexte.getString(R.string.msg_unknown_exported)) }
                .onFailure { afficher(contexte.getString(R.string.msg_unknown_export_failed, it.message.orEmpty())) }
        }
    }

    // --- Installation d'un APK ------------------------------------------------------------

    /**
     * Examine un APK désigné dans le sélecteur d'Android. Il est d'abord copié dans le cache : dadb envoie
     * un fichier, et la lecture du manifeste en demande un aussi. Rien ne part vers le téléviseur avant la
     * confirmation, qui montre le paquet, sa version et ce qu'elle remplace.
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

    /** Envoie puis installe, après confirmation : la barre suit l'envoi, puis l'installation par Android. */
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
            // Les compteurs de paquets ont bougé, et l'application installée est peut-être un launcher.
            rafraichir()
        }
    }

    /** La confirmation refusée : la copie du cache n'a plus de raison d'être. */
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

    /** Le nom sous lequel le fichier se présente — pour l'affichage seulement, jamais pour un chemin. */
    private suspend fun nomAffiche(source: Uri): String = withContext(Dispatchers.IO) {
        runCatching {
            contexte.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { curseur -> if (curseur.moveToFirst()) curseur.getString(0) else null }
        }.getOrNull() ?: source.lastPathSegment ?: NOM_COPIE
    }

    /**
     * Copie l'APK dans le cache, sous un nom fixe : le nom affiché vient d'une autre application et ne
     * compose aucun chemin. Une copie précédente, abandonnée en route, part avec le dossier.
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

    // --- Commande ADB libre ---------------------------------------------------------------

    fun saisirCommande(valeur: String) = majCommande { it.copy(saisie = valeur) }

    /**
     * Envoie la commande saisie, une fois, et garde sa sortie à l'écran. Ni confirmation ni garde-fou : la
     * carte dit ce qu'il en est, et le journal consigne chaque envoi.
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
                // Elle a pu changer ce que montrent les autres onglets : paquets, accueil, compteurs.
                rafraichir()
            }
        }
    }

    private fun majCommande(transformation: (EtatCommande) -> EtatCommande) =
        majEtat { it.copy(commande = transformation(it.commande)) }

    /** « wt » : un fichier réécrit se tronque, sans quoi un contenu plus court laisserait une queue. */
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
