package net.jolabs40.tvslim.soutien

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.fichiers.ResultatDepot
import net.jolabs40.tvslim.fichiers.SignalFichiers
import net.jolabs40.tvslim.installation.ResultatInstallation
import net.jolabs40.tvslim.moteur.ResultatAction

/** Ce que l'application retient de ses invitations, sur l'appareil et nulle part ailleurs. */
data class MemoireSoutien(
    /** La personne a dit avoir déjà donné : on la croit, et on ne lui redemande plus rien. */
    val donDeclare: Boolean = false,
    /** Quand la dernière invitation s'est montrée ; 0 tant qu'aucune ne l'a fait. */
    val derniereInvitation: Long = 0L,
)

/** Où chaque application range [MemoireSoutien] : un fichier JSON sous Windows, le DataStore sur le téléphone. */
interface MagasinSoutien {
    suspend fun lireSoutien(): MemoireSoutien

    suspend fun ecrireSoutien(memoire: MemoireSoutien)
}

/**
 * L'invitation à offrir un café, après un service rendu : un débloat, un transfert de fichiers ou une
 * installation qui ont abouti sans accroc.
 *
 * TV Slim ne parle qu'à GitHub — c'est écrit dans le README — et ne peut donc pas savoir qui a donné. La
 * personne le dit elle-même, d'un bouton, et c'est définitif. Ouvrir la page de soutien est l'affaire du
 * navigateur, après un clic : l'application n'appelle jamais Ko-fi elle-même.
 */
object InvitationSoutien {
    const val LIEN = "https://ko-fi.com/jolabs40"

    /** Entre deux invitations : un service rendu chaque jour ne vaut pas une demande chaque jour. */
    const val INTERVALLE_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun aProposer(memoire: MemoireSoutien, maintenant: Long): Boolean {
        if (memoire.donDeclare) return false
        if (memoire.derniereInvitation <= 0L) return true
        val ecoule = maintenant - memoire.derniereInvitation
        // Une horloge revenue en arrière ne doit pas taire l'invitation pendant des années.
        return ecoule < 0 || ecoule >= INTERVALLE_MS
    }

    /** Un débloat, ou une configuration réinjectée, dont chaque action a abouti. */
    fun merite(resultats: List<ResultatAction>): Boolean = resultats.isNotEmpty() && resultats.all { it.reussi }

    /** Un envoi vers le téléviseur, ou une copie vers l'ordinateur, arrivé au bout sans un fichier refusé. */
    fun merite(depot: ResultatDepot): Boolean = depot.complet && depot.envoyes > 0

    fun merite(signal: SignalFichiers): Boolean = signal is SignalFichiers.Depot && merite(signal.resultat)

    fun merite(installation: ResultatInstallation): Boolean = installation is ResultatInstallation.Reussie
}

/**
 * Le bandeau de soutien, dans les deux applications : il se montre après un service rendu, au plus une fois
 * par [InvitationSoutien.INTERVALLE_MS], et plus jamais une fois le don déclaré.
 *
 * L'invitation est datée au moment où elle se montre, et non quand on la ferme : une fenêtre fermée sans
 * répondre ne doit pas la faire revenir au service suivant.
 */
class PiloteSoutien(
    private val magasin: MagasinSoutien,
    private val portee: CoroutineScope,
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Un service vient d'être rendu : l'invitation se montre, si son heure est venue. */
    fun remercier() {
        if (_visible.value) return
        portee.launch {
            val memoire = magasin.lireSoutien()
            val maintenant = horloge()
            if (!InvitationSoutien.aProposer(memoire, maintenant)) return@launch
            magasin.ecrireSoutien(memoire.copy(derniereInvitation = maintenant))
            _visible.value = true
        }
    }

    /** « Plus tard », ou la page de soutien ouverte : le bandeau s'efface jusqu'à la prochaine échéance. */
    fun ecarter() {
        _visible.value = false
    }

    /** « J'ai déjà fait un don » : plus d'invitation, jamais. */
    fun declarerDon() {
        _visible.value = false
        portee.launch { magasin.ecrireSoutien(magasin.lireSoutien().copy(donDeclare = true)) }
    }
}
