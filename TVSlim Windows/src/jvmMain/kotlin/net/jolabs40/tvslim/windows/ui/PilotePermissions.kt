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
 * Grants TV apps permissions they cannot obtain on their own (`DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
 * `PACKAGE_USAGE_STATS`).
 *
 * The privilege is the ADB session's, the same one used for `pm disable-user`. Only the target differs (a
 * third-party app rather than a catalogue package), hence the engine's safeguards, which reject suspicious
 * input and permissions missing from the manifest.
 *
 * Some permissions are not enough alone: Android also gates them behind an app-op, so both are granted and
 * revoked together. Same logic as the companion, line for line.
 */
class PilotePermissions(
    private val lecteur: LecteurDistant,
    private val moteur: () -> MoteurDebloat?,
    private val portee: CoroutineScope,
    private val afficher: (MessageUi) -> Unit,
) {

    private val _etat = MutableStateFlow(EtatPermissions())
    val etat: StateFlow<EtatPermissions> = _etat.asStateFlow()

    /** Changing package discards what was read for the previous one. */
    fun majPaquet(valeur: String) = _etat.update {
        it.copy(paquet = valeur.trim(), lues = null, paquetLu = "", modeAppOp = "")
    }

    /** An app picked from the list: sets its package and reads what it declares right away. */
    fun choisirPaquet(paquet: String) {
        majPaquet(paquet)
        lire()
    }

    /** Changing permission discards the app-op mode read for the previous one. */
    fun majPermission(valeur: String) = _etat.update {
        it.copy(permission = valeur.trim(), modeAppOp = "")
    }

    /** Called on disconnect: read state belongs to one TV. */
    fun oublier() = _etat.update { EtatPermissions() }

    /** Asks the TV what the app declares and what it has already been granted. */
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
            afficher(texte(Res.string.msg_failure, resultat.texte()))
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
            afficher(texte(Res.string.msg_failure, resultat.texte()))
            return@agir
        }
        // Reset the app-op to "default" rather than "ignore": it may not have been denied before, and
        // "default" lets the permission decide, as originally.
        val complement = poserAppOp(paquet, permission, MODE_DEFAUT, moteurActif)
        afficher(MessageUi.Lignes(listOf(texte(Res.string.msg_perm_revoked, permission), complement)))
        relire(paquet)
    }

    /**
     * Undoes a journal line. Its target is stored as "package name"; the engine's safeguards already
     * rejected anything containing another space.
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
                    // The journal stores the full command; its last word is the target mode.
                    mode = action.commandeAnnulation.substringAfterLast(' '),
                    modePrecedent = lecteur.modeAppOp(paquet, nom),
                )

                // A grant is undone by revoking, and vice versa.
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
                    texte(Res.string.msg_failure, resultat.texte())
                },
            )
            if (paquet == _etat.value.paquet) relire(paquet)
        }
    }

    /**
     * Sets the app-op paired with the permission, if any, and returns a one-line outcome. Skips the
     * round trip when the op is already in the wanted mode.
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
            texte(Res.string.msg_appop_failed, appOp, resultat.texte())
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

    /** Re-reads the package's permissions and paired app-op, and publishes them. */
    private suspend fun relire(paquet: String): PermissionsPaquet {
        val permission = _etat.value.permission
        _etat.update { it.copy(lecture = true) }

        val lues = lecteur.permissions(paquet)
        val mode = APP_OPS_ASSOCIES[permission]
            ?.let { lecteur.modeAppOp(paquet, it) }
            .orEmpty()

        _etat.update { courant ->
            // The user may have changed target during the round trip; if so, drop the result.
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
