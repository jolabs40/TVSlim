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
 * Grants TV apps permissions they cannot obtain themselves (`DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`,
 * `PACKAGE_USAGE_STATS`), using the ADB session's shell privileges.
 *
 * The target is a third-party app rather than a catalogue package, so everything goes through the engine's
 * safeguards, which reject suspicious input and permissions the manifest does not declare.
 *
 * Some permissions are also gated by an app-op set with a separate command; granting one without the other
 * makes `pm grant` succeed while the app still sees nothing. Both are granted and revoked together.
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

    /** Changing the package discards what was read for the previous one. */
    fun majPaquet(valeur: String) = _etat.update {
        it.copy(paquet = valeur.trim(), lues = null, paquetLu = "", modeAppOp = "")
    }

    /** Selects an app from the list and reads its permissions right away. */
    fun choisirPaquet(paquet: String) {
        majPaquet(paquet)
        lire()
    }

    /** Changing the permission discards the app-op mode read for the previous one. */
    fun majPermission(valeur: String) = _etat.update {
        it.copy(permission = valeur.trim(), modeAppOp = "")
    }

    /** Called on disconnect: the state belongs to the previous TV. */
    fun oublier() = _etat.update { EtatPermissions() }

    /** Reads what the app declares and what it has already been granted. */
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
        // Reset the app-op to "default" rather than "ignore": it may not have been denied before, and
        // "default" lets the permission decide.
        val complement = poserAppOp(paquet, permission, MODE_DEFAUT, moteurActif)
        afficher(phrases(contexte.getString(R.string.msg_perm_revoked, permission), complement))
        relire(paquet)
    }

    /**
     * Undoes a log entry. Its target is written as "package name"; the engine's safeguards already rejected
     * anything with an extra space.
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
                    // The log stores the full command; its last word is the target mode.
                    mode = action.commandeAnnulation.substringAfterLast(' '),
                    modePrecedent = lecteur.modeAppOp(paquet, nom),
                )

                // A grant is undone by a revoke, and vice versa.
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
     * Sets the app-op paired with the permission, if any, and returns a one-sentence outcome. Skipped when the
     * op is already in the requested mode.
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

    /** Joins sentences, skipping blank ones. */
    private fun phrases(vararg morceaux: String): String = morceaux.filter { it.isNotBlank() }.joinToString(" ")

    /** Re-reads the package's permissions and paired app-op, and publishes them. */
    private suspend fun relire(paquet: String): PermissionsPaquet {
        val permission = _etat.value.permission
        _etat.update { it.copy(lecture = true) }

        val lues = lecteur.permissions(paquet)
        val mode = APP_OPS_ASSOCIES[permission]
            ?.let { lecteur.modeAppOp(paquet, it) }
            .orEmpty()

        _etat.update { courant ->
            // Discard the result if the user changed the target meanwhile.
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
