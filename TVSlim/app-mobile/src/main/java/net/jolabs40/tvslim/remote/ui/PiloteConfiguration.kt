package net.jolabs40.tvslim.remote.ui

import android.content.Context
import android.net.Uri
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
import net.jolabs40.tvslim.remote.R
import java.io.FileNotFoundException

/**
 * La configuration du téléviseur : son écran d'accueil — la fiche du launcher recommandé, le guet de
 * son installation — et la sauvegarde qu'on réinjecte plus tard, launcher et paquets ensemble.
 *
 * Tirée du pilote principal comme les permissions : elle n'en partage que l'état et le moteur. Les
 * fichiers passent par le sélecteur d'Android : rien n'est écrit ni lu sans qu'on l'ait désigné.
 */
class PiloteConfiguration(
    private val contexte: Context,
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val etat: () -> EtatRemote,
    private val majEtat: ((EtatRemote) -> EtatRemote) -> Unit,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
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
     * Ouvre la fiche d'un launcher dans la boutique du téléviseur. L'installation elle-même se
     * valide à la télécommande : le compagnon ne pose aucun APK sur l'appareil.
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
            runCatching {
                withContext(Dispatchers.IO) {
                    // « wt » : un fichier réécrit se tronque, sans quoi un JSON plus court laisserait une queue.
                    val flux = contexte.contentResolver.openOutputStream(cible, "wt")
                        ?: throw FileNotFoundException(cible.toString())
                    flux.use { it.write(texte.toByteArray()) }
                }
            }
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

    private fun EtatRemote.etats() = lignes.associate { it.entree.paquet to it.etat }

    private companion object {
        const val DUREE_GUET_MS = 3 * 60 * 1000L
        const val INTERVALLE_GUET_MS = 5_000L
    }
}
