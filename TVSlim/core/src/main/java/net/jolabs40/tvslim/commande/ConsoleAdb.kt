package net.jolabs40.tvslim.commande

import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurDirect
import net.jolabs40.tvslim.shell.Interruption

/** Pourquoi une saisie ne part pas. */
enum class RefusCommande { VIDE, PAS_SHELL, TROP_LONGUE }

sealed interface SaisieCommande {
    data class Prete(val commande: String) : SaisieCommande

    data class Refusee(val refus: RefusCommande) : SaisieCommande
}

/** Une commande, et ce qu'elle a rendu. */
data class EchangeCommande(
    val commande: String,
    val code: Int?,
    val sortie: String,
    val interruption: Interruption? = null,
    val motif: String = "",
    /** La longueur de la sortie reçue, avant qu'elle soit tronquée pour l'affichage. */
    val longueurRecue: Int = sortie.length,
) {
    val reussie: Boolean get() = code == 0
    val tronquee: Boolean get() = longueurRecue > sortie.length
}

/**
 * La commande ADB libre : ce que les autres cartes ne font pas, tapé à la main.
 *
 * C'est la seule porte de TV Slim qui ne passe par aucun garde-fou, et c'est assumé : une liste noire se
 * contourne (`cmd package uninstall`, `sh -c "…"`), elle ne ferait que donner une fausse assurance. Ce qui
 * reste tenu : la commande part **une seule fois**, jamais rejouée après une rupture ; elle est consignée
 * au journal, sans commande d'annulation ; et l'écran dit qu'elle échappe au reste.
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
        /** Le délai maximal d'une commande libre : au-delà, elle est coupée, et sa sortie rendue. */
        const val DELAI_MAX_S = 30

        /** Au-delà, la sortie est tronquée pour l'affichage : `dumpsys` écrit à lui seul des centaines de Ko. */
        const val SORTIE_MAX = 200_000

        const val LONGUEUR_MAX = 4_000
        private const val CIBLE_MAX = 300
        private const val MESSAGE_MAX = 300

        /** `adb`, ses options — celles qui prennent une valeur d'abord — puis `shell`. */
        private val ENVELOPPE = Regex("""^adb(?:\s+-[stHPL]\s+\S+|\s+-\S+)*\s+shell(?:\s+|$)""")

        /**
         * Ce qui partira, ou pourquoi rien ne part. « adb shell pm list packages », collé d'un tutoriel,
         * perd son enveloppe — `-s <appareil>` compris — et une paire de guillemets qui entourait toute la
         * commande, que le shell de l'ordinateur aurait retirée. Les autres commandes d'`adb` (`install`,
         * `push`, `reboot`…) ne passent pas par le shell du téléviseur : refusées.
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
