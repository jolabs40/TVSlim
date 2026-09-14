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
import net.jolabs40.tvslim.windows.ressources.msg_launcher_installed
import net.jolabs40.tvslim.windows.ressources.msg_store_failed
import net.jolabs40.tvslim.windows.ressources.msg_store_opened
import net.jolabs40.tvslim.windows.ressources.msg_unknown_export_failed
import net.jolabs40.tvslim.windows.ressources.msg_unknown_exported
import net.jolabs40.tvslim.windows.ressources.msg_unknown_proposed
import net.jolabs40.tvslim.windows.ressources.msg_unknown_reading
import java.io.File

/**
 * La configuration du téléviseur : son écran d'accueil — la fiche du launcher recommandé, le guet de
 * son installation — et la sauvegarde qu'on réinjecte plus tard, launcher et paquets ensemble. S'y ajoutent
 * l'inventaire de ce que le catalogue ignore, relevé et exporté à la demande, l'installation d'un APK
 * qu'on a sous la main, et la commande ADB libre.
 *
 * Tirée du pilote principal comme les permissions : elle n'en partage que l'état et le moteur, et la
 * réinjection passe par les mêmes garde-fous qu'une application en lot.
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
) {

    /** Guette l'arrivée d'un launcher que l'on vient d'envoyer installer. */
    private var guet: Job? = null

    /** Appelé à la déconnexion : le guet et le bilan d'installation ne valent que pour le téléviseur quitté. */
    fun oublier() {
        guet?.cancel()
        guet = null
        majInstallation { EtatInstallation() }
        // Les commandes déjà tapées restent à portée de ↑ ; la sortie, elle, était celle du téléviseur quitté.
        majCommande { EtatCommande(saisie = it.saisie, historique = it.historique) }
    }

    // --- Écran d'accueil ------------------------------------------------------------------

    /**
     * Ouvre la fiche d'un launcher dans la boutique du téléviseur. L'installation se valide à la
     * télécommande, et vient de la boutique : c'est le chemin recommandé, là où un APK existe aussi.
     */
    fun installerLauncher(paquet: String) {
        val moteurActif = moteur() ?: return afficher(texte(Res.string.msg_connect_first))
        portee.launch {
            val resultat = moteurActif.ouvrirFicheBoutique(paquet)
            if (!resultat.reussi) {
                afficher(texte(Res.string.msg_store_failed, resultat.message))
                return@launch
            }
            afficher(texte(Res.string.msg_store_opened))
            guetterInstallation(paquet)
        }
    }

    /**
     * Guette l'arrivée du launcher plutôt que d'exiger un « Actualiser » : la personne est devant
     * son téléviseur, pas devant l'écran. Une question courte toutes les cinq secondes, trois minutes.
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

    // --- Sauvegarde et réinjection --------------------------------------------------------

    /** Nom proposé par la fenêtre d'enregistrement : l'appareil et le jour. */
    fun nomFichier(): String = FichierConfiguration.nomPropose(etat().infos)

    /** Écrit la configuration du téléviseur tel qu'il a été lu en dernier. */
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
     * Relit une sauvegarde et la compare au téléviseur. Rien ne part : ce qui changerait est soumis à
     * confirmation, et un téléviseur déjà conforme le dit sans ouvrir de fenêtre.
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

    /** Réinjecte après confirmation, avec la progression et le bilan d'une application en lot. */
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
     *
     * Avec [puisOuvrir], le formulaire du catalogue s'ouvre ensuite dans le navigateur : la personne y joint
     * le fichier et l'envoie elle-même.
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

    // --- Installation d'un APK ------------------------------------------------------------

    /**
     * Examine un APK choisi ou glissé dans la fenêtre : ce qu'il est, et ce que le téléviseur en porte
     * déjà. Rien ne part avant la confirmation, qui montre le paquet, sa version et ce qu'elle remplace.
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

    /** Envoie puis installe, après confirmation : la barre suit l'envoi, puis l'installation par Android. */
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
            // Les compteurs de paquets ont bougé, et l'application installée est peut-être un launcher.
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

    // --- Commande ADB libre ---------------------------------------------------------------

    fun saisirCommande(valeur: String) = majCommande { it.copy(saisie = valeur, rappel = -1) }

    /** ↑ et ↓ dans le champ, comme dans un terminal. */
    fun rappelerCommande(plusAncienne: Boolean) = majCommande { it.avecRappel(plusAncienne) }

    /**
     * Envoie la commande saisie, une fois, et garde sa sortie à l'écran. Ni confirmation ni garde-fou : la
     * carte dit ce qu'il en est, et le journal consigne chaque envoi.
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
                // Elle a pu changer ce que montrent les autres onglets : paquets, accueil, compteurs.
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
