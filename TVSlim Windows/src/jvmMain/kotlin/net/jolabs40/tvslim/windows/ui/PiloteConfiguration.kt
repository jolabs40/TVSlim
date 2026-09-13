package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.jolabs40.tvslim.configuration.FichierConfiguration
import net.jolabs40.tvslim.configuration.PlanReinjection
import net.jolabs40.tvslim.configuration.Reinjecteur
import net.jolabs40.tvslim.configuration.configurationDe
import net.jolabs40.tvslim.configuration.planifier
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.windows.ressources.Res
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
import java.io.File

/**
 * La configuration du téléviseur : son écran d'accueil — la fiche du launcher recommandé, le guet de
 * son installation — et la sauvegarde qu'on réinjecte plus tard, launcher et paquets ensemble.
 *
 * Tirée du pilote principal comme les permissions : elle n'en partage que l'état et le moteur, et la
 * réinjection passe par les mêmes garde-fous qu'une application en lot.
 */
class PiloteConfiguration(
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val etat: () -> EtatApp,
    private val majEtat: ((EtatApp) -> EtatApp) -> Unit,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
    private val rafraichir: () -> Unit,
    private val terminer: (List<ResultatAction>) -> Unit,
) {

    /** Guette l'arrivée d'un launcher que l'on vient d'envoyer installer. */
    private var guet: Job? = null

    /** Appelé à la déconnexion : le guet ne vaut que pour le téléviseur quitté. */
    fun oublier() {
        guet?.cancel()
        guet = null
    }

    // --- Écran d'accueil ------------------------------------------------------------------

    /**
     * Ouvre la fiche d'un launcher dans la boutique du téléviseur. L'installation se valide à la
     * télécommande : l'application ne pose aucun APK sur l'appareil.
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

    private fun EtatApp.etats() = lignes.associate { it.entree.paquet to it.etat }

    private companion object {
        const val DUREE_GUET_MS = 3 * 60 * 1000L
        const val INTERVALLE_GUET_MS = 5_000L
    }
}
