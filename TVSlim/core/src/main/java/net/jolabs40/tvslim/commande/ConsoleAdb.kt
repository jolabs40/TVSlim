package net.jolabs40.tvslim.commande

import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurDirect
import net.jolabs40.tvslim.shell.Interruption

/** Why typed input is not sent. */
enum class RefusCommande { VIDE, PAS_SHELL, TROP_LONGUE }

sealed interface SaisieCommande {
    data class Prete(val commande: String) : SaisieCommande

    data class Refusee(val refus: RefusCommande) : SaisieCommande
}

data class EchangeCommande(
    val commande: String,
    val code: Int?,
    val sortie: String,
    val interruption: Interruption? = null,
    val motif: String = "",
    /** Length of the output received, before truncation for display. */
    val longueurRecue: Int = sortie.length,
) {
    val reussie: Boolean get() = code == 0
    val tronquee: Boolean get() = longueurRecue > sortie.length
}

/**
 * Free-form `adb shell` command typed by hand.
 *
 * The only path in TV Slim that bypasses every safeguard, on purpose: a blocklist is easy to evade
 * (`cmd package uninstall`, `sh -c "..."`) and would only give false assurance. Instead the command is sent
 * once, never replayed after a disconnect, and logged to the journal with no undo command.
 */
class ConsoleAdb(
    private val executeur: ExecuteurDirect,
    private val journal: () -> JournalRepository?,
) {

    suspend fun envoyer(commande: String): EchangeCommande {
        val reponse = executeur.executerUneFois(commande)
        val echange = EchangeCommande(
            commande = commande,
            code = reponse.code,
            sortie = reponse.sortie.take(SORTIE_MAX),
            interruption = reponse.interruption,
            motif = reponse.motif,
            longueurRecue = reponse.sortie.length,
        )
        journal()?.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.COMMANDE,
                cible = commande.take(CIBLE_MAX),
                libelle = "Commande libre",
                commandeAnnulation = "",
                reussi = echange.reussie,
                message = if (echange.reussie) "" else motifEchec(echange),
            ),
        )
        return echange
    }

    private fun motifEchec(echange: EchangeCommande): String = when (echange.interruption) {
        Interruption.DELAI -> "Coupée par le délai maximal."
        Interruption.CONNEXION -> echange.motif.ifBlank { "Connexion perdue." }
        null -> echange.sortie.trim().take(MESSAGE_MAX).ifBlank { "Code de retour ${echange.code}." }
    }

    companion object {
        /** Past this, the command is cut off and the output so far is returned. */
        const val DELAI_MAX_S = 30

        /** Output beyond this is truncated for display: `dumpsys` alone prints hundreds of KB. */
        const val SORTIE_MAX = 200_000

        const val LONGUEUR_MAX = 4_000
        private const val CIBLE_MAX = 300
        private const val MESSAGE_MAX = 300

        /** `adb`, its options (those taking a value first), then `shell`. */
        private val ENVELOPPE = Regex("""^adb(?:\s+-[stHPL]\s+\S+|\s+-\S+)*\s+shell(?:\s+|$)""")

        /**
         * Returns the command to send, or why nothing is sent. `adb shell pm list packages` pasted from a
         * tutorial loses its `adb [-s <device>] shell` prefix, and a pair of quotes around the whole command
         * that the computer's shell would have removed. Other `adb` commands (`install`, `push`, `reboot`...)
         * do not run in the TV's shell and are rejected.
         */
        fun lire(saisie: String): SaisieCommande {
            val texte = saisie.trim()
            if (texte.length > LONGUEUR_MAX) return SaisieCommande.Refusee(RefusCommande.TROP_LONGUE)
            val commande = if (texte == "adb" || texte.startsWith("adb ")) {
                val enveloppe = ENVELOPPE.find(texte) ?: return SaisieCommande.Refusee(RefusCommande.PAS_SHELL)
                sansGuillemets(texte.substring(enveloppe.range.last + 1).trim())
            } else {
                texte
            }
            return if (commande.isEmpty()) {
                SaisieCommande.Refusee(RefusCommande.VIDE)
            } else {
                SaisieCommande.Prete(commande)
            }
        }

        private fun sansGuillemets(commande: String): String {
            if (commande.length < 2) return commande
            val guillemet = commande.first().takeIf { it == '\'' || it == '"' } ?: return commande
            val interieur = commande.substring(1, commande.length - 1)
            return if (commande.last() == guillemet && guillemet !in interieur) interieur.trim() else commande
        }
    }
}
