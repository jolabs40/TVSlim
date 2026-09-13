package net.jolabs40.tvslim.windows.ui

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
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.msg_appop_failed
import net.jolabs40.tvslim.windows.ressources.msg_appop_set
import net.jolabs40.tvslim.windows.ressources.msg_connect_first
import net.jolabs40.tvslim.windows.ressources.msg_failure
import net.jolabs40.tvslim.windows.ressources.msg_not_undoable
import net.jolabs40.tvslim.windows.ressources.msg_perm_enter_both
import net.jolabs40.tvslim.windows.ressources.msg_perm_enter_package
import net.jolabs40.tvslim.windows.ressources.msg_perm_granted
import net.jolabs40.tvslim.windows.ressources.msg_perm_not_found
import net.jolabs40.tvslim.windows.ressources.msg_perm_reopen
import net.jolabs40.tvslim.windows.ressources.msg_perm_revoked
import net.jolabs40.tvslim.windows.ressources.msg_undone

/**
 * Accorde aux applications du téléviseur les permissions qu'aucune d'elles ne peut s'attribuer
 * seule — `DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`, `PACKAGE_USAGE_STATS`.
 *
 * Le privilège est celui de la session ADB, le même qui sert à `pm disable-user`. Ce qui change est
 * la cible — une application tierce plutôt qu'un paquet du catalogue — d'où le passage obligé par
 * les garde-fous du moteur, qui refusent une saisie douteuse et une permission absente du manifeste.
 *
 * Certaines permissions ne suffisent pas seules : Android les double d'un **app-op**. Les deux
 * tombent donc ensemble, et se rendent ensemble. Même logique que le compagnon, ligne pour ligne ;
 * seuls les messages passent désormais par les ressources traduites.
 */
class PilotePermissions(
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
) {

    private val _etat = MutableStateFlow(EtatPermissions())
    val etat: StateFlow<EtatPermissions> = _etat.asStateFlow()

    /** Changer de paquet périme la lecture : on ne garde pas l'état d'une autre application. */
    fun majPaquet(valeur: String) = _etat.update {
        it.copy(paquet = valeur.trim(), lues = null, paquetLu = "", modeAppOp = "")
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
            afficher(texte(Res.string.msg_perm_enter_package))
            return
        }
        if (moteur() == null) {
            afficher(texte(Res.string.msg_connect_first))
            return
        }
        portee.launch { relire(paquet) }
    }

    fun accorder() = agir { paquet, permission, moteurActif ->
        val lues = relire(paquet)
        if (!lues.paquetTrouve) {
            afficher(texte(Res.string.msg_perm_not_found, paquet))
            return@agir
        }
        val resultat = moteurActif.accorderPermission(paquet, permission, lues.demandees)
        if (!resultat.reussi) {
            afficher(texte(Res.string.msg_failure, resultat.message))
            return@agir
        }
        val complement = poserAppOp(paquet, permission, MODE_AUTORISE, moteurActif)
        afficher(
            MessageUi.Lignes(
                listOf(
                    texte(Res.string.msg_perm_granted, permission),
                    complement,
                    texte(Res.string.msg_perm_reopen),
                ),
            ),
        )
        relire(paquet)
    }

    fun retirer() = agir { paquet, permission, moteurActif ->
        val resultat = moteurActif.retirerPermission(paquet, permission)
        if (!resultat.reussi) {
            afficher(texte(Res.string.msg_failure, resultat.message))
            return@agir
        }
        // On rend l'app-op à « default » plutôt qu'à « ignore » : rien ne dit qu'il était refusé
        // avant notre passage, et « default » laisse la permission trancher, comme à l'origine.
        val complement = poserAppOp(paquet, permission, MODE_DEFAUT, moteurActif)
        afficher(MessageUi.Lignes(listOf(texte(Res.string.msg_perm_revoked, permission), complement)))
        relire(paquet)
    }

    /**
     * Annule une ligne du journal. La cible y est écrite « paquet nom » — les garde-fous du moteur
     * ont déjà écarté tout ce qui contiendrait un espace de plus.
     */
    fun annuler(action: ActionJournal) {
        val moteurActif = moteur()
        val morceaux = action.cible.split(' ')
        if (moteurActif == null || morceaux.size != 2) {
            afficher(texte(Res.string.msg_not_undoable))
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
            afficher(
                if (resultat.reussi) {
                    texte(Res.string.msg_undone)
                } else {
                    texte(Res.string.msg_failure, resultat.message)
                },
            )
            if (paquet == _etat.value.paquet) relire(paquet)
        }
    }

    /**
     * Pose l'app-op qui double la permission, s'il y en a un, et dit en une phrase ce qu'il en est
     * advenu. Un op déjà dans le mode voulu ne coûte pas d'aller-retour.
     */
    private suspend fun poserAppOp(
        paquet: String,
        permission: String,
        mode: String,
        moteurActif: MoteurDebloat,
    ): MessageUi {
        val appOp = APP_OPS_ASSOCIES[permission] ?: return MessageUi.Brut("")
        val actuel = lecteur.modeAppOp(paquet, appOp)
        if (actuel == mode) return MessageUi.Brut("")

        val resultat = moteurActif.reglerAppOp(paquet, appOp, mode, actuel)
        return if (resultat.reussi) {
            texte(Res.string.msg_appop_set, appOp, mode)
        } else {
            texte(Res.string.msg_appop_failed, appOp, resultat.message)
        }
    }

    private fun agir(
        bloc: suspend (paquet: String, permission: String, moteur: MoteurDebloat) -> Unit,
    ) {
        val courant = _etat.value
        val moteurActif = moteur()
        when {
            moteurActif == null -> afficher(texte(Res.string.msg_connect_first))
            !courant.saisieComplete -> afficher(texte(Res.string.msg_perm_enter_both))
            else -> portee.launch { bloc(courant.paquet, courant.permission, moteurActif) }
        }
    }

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
                courant.copy(lecture = false, lues = lues, paquetLu = paquet, modeAppOp = mode)
            }
        }
        return lues
    }

    private companion object {
        const val MODE_AUTORISE = "allow"
        const val MODE_DEFAUT = "default"
    }
}
