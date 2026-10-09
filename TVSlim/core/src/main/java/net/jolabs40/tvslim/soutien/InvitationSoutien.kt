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

/** What the app remembers about its invitations, stored on the device only. */
data class MemoireSoutien(
    /** The user said they already donated: taken at their word, never asked again. */
    val donDeclare: Boolean = false,
    /** When the last invitation was shown; 0 if never. */
    val derniereInvitation: Long = 0L,
)

/** Storage for [MemoireSoutien]: a JSON file on Windows, DataStore on the phone. */
interface MagasinSoutien {
    suspend fun lireSoutien(): MemoireSoutien

    suspend fun ecrireSoutien(memoire: MemoireSoutien)
}

/**
 * The invitation to buy a coffee after a successful debloat, file transfer or installation.
 *
 * TV Slim only talks to GitHub (as the README states), so it cannot know who donated. The user says so
 * with a button, and that is final. The support page opens in the browser after a click; the app never
 * calls Ko-fi itself.
 */
object InvitationSoutien {
    const val LIEN = "https://ko-fi.com/jolabs40"

    /** Minimum time between invitations, so daily use does not mean a daily request. */
    const val INTERVALLE_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun aProposer(memoire: MemoireSoutien, maintenant: Long): Boolean {
        if (memoire.donDeclare) return false
        if (memoire.derniereInvitation <= 0L) return true
        val ecoule = maintenant - memoire.derniereInvitation
        // A clock set backwards must not silence the invitation for years.
        return ecoule < 0 || ecoule >= INTERVALLE_MS
    }

    /** A debloat or reapplied configuration where every action succeeded. */
    fun merite(resultats: List<ResultatAction>): Boolean = resultats.isNotEmpty() && resultats.all { it.reussi }

    /** An upload to the TV or a copy to the PC that completed with no rejected file. */
    fun merite(depot: ResultatDepot): Boolean = depot.complet && depot.envoyes > 0

    fun merite(signal: SignalFichiers): Boolean = signal is SignalFichiers.Depot && merite(signal.resultat)

    fun merite(installation: ResultatInstallation): Boolean = installation is ResultatInstallation.Reussie
}

/**
 * The support banner, in both apps: shown after a successful action, at most once per
 * [InvitationSoutien.INTERVALLE_MS], and never again once a donation is declared.
 *
 * The invitation is timestamped when shown, not when dismissed, so closing it without answering does
 * not bring it back on the next action.
 */
class PiloteSoutien(
    private val magasin: MagasinSoutien,
    private val portee: CoroutineScope,
    private val horloge: () -> Long = System::currentTimeMillis,
) {

    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    /** Called after a successful action; shows the invitation if it is due. */
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

    /** "Later", or the support page was opened: hides the banner until the next invitation is due. */
    fun ecarter() {
        _visible.value = false
    }

    /** "I already donated": no more invitations, ever. */
    fun declarerDon() {
        _visible.value = false
        portee.launch { magasin.ecrireSoutien(magasin.lireSoutien().copy(donDeclare = true)) }
    }
}
