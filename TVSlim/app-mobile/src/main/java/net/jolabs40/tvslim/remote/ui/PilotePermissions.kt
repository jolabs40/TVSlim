package net.jolabs40.tvslim.remote.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.jolabs40.tvslim.device.LecteurDistant
import net.jolabs40.tvslim.device.PermissionsPaquet
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.moteur.MoteurDebloat
import net.jolabs40.tvslim.moteur.ResultatAction
import net.jolabs40.tvslim.remote.R

/**
 * Accorde aux applications du téléviseur les permissions qu'aucune d'elles ne peut s'attribuer
 * seule — `DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `PACKAGE_USAGE_STATS`.
 *
 * Le privilège n'a rien de nouveau : c'est celui de la session ADB, le même qui sert à
 * `pm disable-user`. Ce qui change est la cible — une application tierce plutôt qu'un paquet du
 * catalogue — d'où le passage obligé par les garde-fous du moteur, qui refusent une saisie
 * douteuse et une permission absente du manifeste.
 *
 * Certaines permissions ne suffisent pas seules : Android les double d'un **app-op**, posé par
 * une commande distincte. Accorder l'une sans l'autre donne un `pm grant` réussi et une
 * application qui ne voit toujours rien — les deux tombent donc ensemble, et se rendent
 * ensemble.
 *
 * Vit à côté du [RemoteViewModel] plutôt qu'en son sein : le pilote frôlait déjà les cinq cents
 * lignes, et la carte des permissions a son propre état, sans rapport avec le reste.
 */
class PilotePermissions(
    private val contexte: Context,
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val portee: CoroutineScope,
    private val afficher: (String) -> Unit,
) {

    private val _etat = MutableStateFlow(EtatPermissions())
    val etat: StateFlow<EtatPermissions> = _etat.asStateFlow()

    /** Changer de paquet périme la lecture : on ne garde pas l'état d'une autre application. */
    fun majPaquet(valeur: String) = _etat.update {
        it.copy(paquet = valeur.trim(), lues = null, paquetLu = "", modeAppOp = "")
    }

    /** Une application choisie dans la liste : son paquet, puis ce qu'elle déclare, lu aussitôt. */
    fun choisirPaquet(paquet: String) {
        majPaquet(paquet)
        lire()
    }

    /** Changer de permission périme le mode lu : il ne vaut que pour l'app-op de la précédente. */
    fun majPermission(valeur: String) = _etat.update {
        it.copy(permission = valeur.trim(), modeAppOp = "")
    }

    /** Oublie tout : appelé à la déconnexion, l'état lu ne vaut que pour un téléviseur donné. */
    fun oublier() = _etat.update { EtatPermissions() }

    /** Demande au téléviseur ce que l'application déclare et ce qu'elle a déjà obtenu. */
    fun lire() {
        val paquet = _etat.value.paquet
        if (paquet.isBlank()) {
            afficher(contexte.getString(R.string.msg_perm_enter_package))
            return
        }
        if (moteur() == null) {
            afficher(contexte.getString(R.string.msg_connect_first))
            return
        }
        portee.launch { relire(paquet) }
    }

    fun accorder() = agir { paquet, permission, moteurActif ->
        val lues = relire(paquet)
        if (!lues.paquetTrouve) {
            afficher(contexte.getString(R.string.msg_perm_not_found, paquet))
            return@agir
        }
        val resultat = moteurActif.accorderPermission(paquet, permission, lues.demandees)
        if (!resultat.reussi) {
            afficher(echec(resultat))
            return@agir
        }
        val complement = poserAppOp(paquet, permission, MODE_AUTORISE, moteurActif)
        afficher(
            phrases(
                contexte.getString(R.string.msg_perm_granted, permission),
                complement,
                contexte.getString(R.string.msg_perm_reopen),
            ),
        )
        relire(paquet)
    }

    fun retirer() = agir { paquet, permission, moteurActif ->
        val resultat = moteurActif.retirerPermission(paquet, permission)
        if (!resultat.reussi) {
            afficher(echec(resultat))
            return@agir
        }
        // On rend l'app-op à « default » plutôt qu'à « ignore » : rien ne dit qu'il était refusé
        // avant notre passage, et « default » laisse la permission trancher, comme à l'origine.
        val complement = poserAppOp(paquet, permission, MODE_DEFAUT, moteurActif)
        afficher(phrases(contexte.getString(R.string.msg_perm_revoked, permission), complement))
        relire(paquet)
    }

    /**
     * Annule une ligne du journal. La cible y est écrite « paquet nom » — les garde-fous du
     * moteur ont déjà écarté tout ce qui contiendrait un espace de plus.
     */
    fun annuler(action: ActionJournal) {
        val moteurActif = moteur()
        val morceaux = action.cible.split(' ')
        if (moteurActif == null || morceaux.size != 2) {
            afficher(contexte.getString(R.string.msg_not_undoable))
            return
        }
        portee.launch {
            val (paquet, nom) = morceaux
            val resultat = when {
                action.type == TypeAction.APP_OP -> moteurActif.reglerAppOp(
                    paquet = paquet,
                    appOp = nom,
                    // Le journal porte la commande complète : son dernier mot est le mode visé.
                    mode = action.commandeAnnulation.substringAfterLast(' '),
                    modePrecedent = lecteur.modeAppOp(paquet, nom),
                )

                // Accorder s'annule en retirant, et l'inverse.
                action.commandeAnnulation.contains(" revoke ") ->
                    moteurActif.retirerPermission(paquet, nom)

                else -> moteurActif.accorderPermission(
                    paquet = paquet,
                    permission = nom,
                    permissionsDeclarees = lecteur.permissions(paquet).demandees,
                )
            }
            afficher(if (resultat.reussi) contexte.getString(R.string.msg_undone) else echec(resultat))
            if (paquet == _etat.value.paquet) relire(paquet)
        }
    }

    /**
     * Pose l'app-op qui double la permission, s'il y en a un, et dit en une phrase ce qu'il en
     * est advenu. Un op déjà dans le mode voulu ne coûte pas d'aller-retour.
     */
    private suspend fun poserAppOp(
        paquet: String,
        permission: String,
        mode: String,
        moteurActif: MoteurDebloat,
    ): String {
        val appOp = APP_OPS_ASSOCIES[permission] ?: return ""
        val actuel = lecteur.modeAppOp(paquet, appOp)
        if (actuel == mode) return ""

        val resultat = moteurActif.reglerAppOp(paquet, appOp, mode, actuel)
        return if (resultat.reussi) {
            contexte.getString(R.string.msg_appop_set, appOp, mode)
        } else {
            contexte.getString(R.string.msg_appop_failed, appOp, resultat.texte(contexte))
        }
    }

    private fun agir(
        bloc: suspend (paquet: String, permission: String, moteur: MoteurDebloat) -> Unit,
    ) {
        val courant = _etat.value
        val moteurActif = moteur()
        when {
            moteurActif == null -> afficher(contexte.getString(R.string.msg_connect_first))
            !courant.saisieComplete -> afficher(contexte.getString(R.string.msg_perm_enter_both))
            else -> portee.launch {
                bloc(courant.paquet, courant.permission, moteurActif)
            }
        }
    }

    private fun echec(resultat: ResultatAction): String = contexte.getString(R.string.msg_failure, resultat.texte(contexte))

    /** Des phrases bout à bout, les vides sautées — l'app-op n'a pas toujours quelque chose à dire. */
    private fun phrases(vararg morceaux: String): String = morceaux.filter { it.isNotBlank() }.joinToString(" ")

    /** Relit l'état du paquet — permissions et app-op associé — et le publie. */
    private suspend fun relire(paquet: String): PermissionsPaquet {
        val permission = _etat.value.permission
        _etat.update { it.copy(lecture = true) }

        val lues = lecteur.permissions(paquet)
        val mode = APP_OPS_ASSOCIES[permission]
            ?.let { lecteur.modeAppOp(paquet, it) }
            .orEmpty()

        _etat.update { courant ->
            // La personne a pu changer de cible pendant l'aller-retour : dans ce cas, on jette.
            if (courant.paquet != paquet || courant.permission != permission) {
                courant.copy(lecture = false)
            } else {
                courant.copy(
                    lecture = false,
                    lues = lues,
                    paquetLu = paquet,
                    modeAppOp = mode,
                )
            }
        }
        return lues
    }

    private companion object {
        const val MODE_AUTORISE = "allow"
        const val MODE_DEFAUT = "default"
    }
}
