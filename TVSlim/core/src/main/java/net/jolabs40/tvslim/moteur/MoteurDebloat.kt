package net.jolabs40.tvslim.moteur

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet
import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.journal.ActionJournal
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.TypeAction
import net.jolabs40.tvslim.shell.ExecuteurCommande

data class ResultatAction(
    val paquet: String,
    val nom: String,
    val reussi: Boolean,
    /** The TV's raw answer. */
    val message: String = "",
    /** The engine's reason, worded by each app; takes precedence over [message] when set. */
    val motif: MotifMoteur? = null,
)

/**
 * Applies and undoes package disabling, with the safeguards learned from the manual debloat of the
 * TCL:
 *
 *  - never `pm uninstall`: only `pm disable-user --user 0`, undone by `pm enable`;
 *  - blocklisted catalogue packages are always refused;
 *  - the stock home screen is not touched until a third-party launcher is installed;
 *  - the declared order is kept so `setupwraith` goes before `launcherx`; otherwise its
 *    priority-1 RecoveryActivity would take over instead of the chosen launcher.
 *
 * The engine does not know which channel the commands go through (local service or ADB). It writes
 * no text either: its refusals are [MotifMoteur] values that each app words.
 */
class MoteurDebloat(
    private val executeur: ExecuteurCommande,
    private val journal: JournalRepository,
) {

    suspend fun desactiver(
        entrees: List<EntreePaquet>,
        catalogue: Catalogue,
        etats: Map<String, EtatPaquet>,
        launchersDisponibles: Boolean,
        surProgression: (fait: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ResultatAction> {
        val resultats = mutableListOf<ResultatAction>()
        val aJournaliser = mutableListOf<ActionJournal>()

        val aTraiter = entrees.sortedBy { it.ordre }
        aTraiter.forEachIndexed { rang, entree ->
            surProgression(rang, aTraiter.size)
            val refus = motifDeRefus(entree, catalogue, launchersDisponibles)
            if (refus != null) {
                resultats += ResultatAction(entree.paquet, entree.nom, false, motif = refus)
                return@forEachIndexed
            }
            when (etats[entree.paquet] ?: EtatPaquet.ABSENT) {
                EtatPaquet.ABSENT -> {
                    resultats += ResultatAction(entree.paquet, entree.nom, false, motif = MotifMoteur.PaquetAbsent)
                    return@forEachIndexed
                }

                EtatPaquet.DESACTIVE -> {
                    resultats += ResultatAction(entree.paquet, entree.nom, true, motif = MotifMoteur.DejaDesactive)
                    return@forEachIndexed
                }

                EtatPaquet.ACTIF -> Unit
            }

            val sortie = executeur.executer("pm disable-user --user 0 ${entree.paquet}")
            val reussi = sortie.reussi && sortie.sortie.contains("disabled-user")
            val message = if (reussi) "" else sortie.sortie

            resultats += ResultatAction(entree.paquet, entree.nom, reussi, message, motifSiMuet(reussi, message))
            aJournaliser += ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.DESACTIVATION,
                cible = entree.paquet,
                libelle = entree.nom,
                commandeAnnulation = "pm enable ${entree.paquet}",
                reussi = reussi,
                message = message,
            )
        }

        surProgression(aTraiter.size, aTraiter.size)
        journal.ajouter(aJournaliser)
        return resultats
    }

    suspend fun reactiver(
        paquets: List<String>,
        surProgression: (fait: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ResultatAction> {
        val resultats = mutableListOf<ResultatAction>()
        val aJournaliser = mutableListOf<ActionJournal>()

        paquets.forEachIndexed { rang, paquet ->
            surProgression(rang, paquets.size)
            val sortie = executeur.executer("pm enable $paquet")
            val reussi = sortie.reussi && sortie.sortie.contains("enabled")
            val message = if (reussi) "" else sortie.sortie

            resultats += ResultatAction(paquet, paquet, reussi, message, motifSiMuet(reussi, message))
            aJournaliser += ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.REACTIVATION,
                cible = paquet,
                libelle = paquet,
                commandeAnnulation = "pm disable-user --user 0 $paquet",
                reussi = reussi,
                message = message,
            )
        }

        surProgression(paquets.size, paquets.size)
        journal.ajouter(aJournaliser)
        return resultats
    }

    /**
     * Sets the home activity. Call only once the stock home screen is disabled: while it is enabled,
     * the command answers `Success` with no effect.
     *
     * The result is named after the component, which a failure summary can quote untranslated.
     */
    suspend fun definirAccueil(composant: String, ancienAccueil: String): ResultatAction {
        // Both go into commands, and the undo one is replayed as is from the journal long after.
        // They come from `cmd package` output, a constrained source, but checking only one of them
        // would leave the replay unchecked.
        val refus = motifDeRefusComposant(composant) ?: motifDeRefusComposant(ancienAccueil)
        if (refus != null) return ResultatAction(composant, composant, false, motif = refus)

        val sortie = executeur.executer("cmd package set-home-activity $composant")
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.ACCUEIL,
                cible = composant,
                // Journal screens name this type themselves; this label is only used by the export.
                libelle = LIBELLE_ACCUEIL,
                commandeAnnulation = "cmd package set-home-activity $ancienAccueil",
                reussi = sortie.reussi,
                message = if (sortie.reussi) "" else sortie.sortie,
            ),
        )
        return ResultatAction(composant, composant, sortie.reussi, sortie.sortie)
    }

    /**
     * Opens an app's page in the TV's own store; the person at the TV confirms the install with
     * the remote.
     *
     * This is the path for a recommended launcher: nothing is downloaded, the install comes from
     * the official store. An APK the user already has goes through `InstallationApk`, on request.
     */
    suspend fun ouvrirFicheBoutique(paquet: String): ResultatAction {
        if (!IDENTIFIANT.matches(paquet)) {
            return ResultatAction(paquet, paquet, false, motif = MotifMoteur.NomInvalide(NatureNom.PAQUET, paquet))
        }
        val sortie = executeur.executer(
            "am start -a android.intent.action.VIEW -d market://details?id=$paquet",
        )
        return ResultatAction(paquet, paquet, sortie.reussi, sortie.sortie)
    }

    /**
     * Force-stops an app's processes. Nothing is journaled: it is a reset, not a state change, and
     * the app restarts as soon as it is opened or a service calls it.
     */
    suspend fun forcerArret(paquet: String): ResultatAction {
        // This name is parsed from `dumpsys meminfo` output, the only one in the engine not taken
        // from the catalogue or a package list.
        if (!IDENTIFIANT.matches(paquet)) {
            return ResultatAction(paquet, paquet, false, motif = MotifMoteur.NomInvalide(NatureNom.PAQUET, paquet))
        }
        val sortie = executeur.executer("am force-stop $paquet")
        return ResultatAction(paquet, paquet, sortie.reussi, sortie.sortie, motifSiMuet(sortie.reussi, sortie.sortie))
    }

    /** Writes a system setting value and journals its undo command. */
    suspend fun ecrireReglage(
        cle: String,
        portee: String,
        nom: String,
        valeur: String,
        valeurPrecedente: String,
    ): ResultatAction {
        val sortie = executeur.executer("settings put $portee $cle $valeur")
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.REGLAGE,
                cible = cle,
                libelle = nom,
                commandeAnnulation = "settings put $portee $cle $valeurPrecedente",
                reussi = sortie.reussi,
                message = if (sortie.reussi) "" else sortie.sortie,
            ),
        )
        return ResultatAction(cle, nom, sortie.reussi, sortie.sortie, motifSiMuet(sortie.reussi, sortie.sortie))
    }

    /**
     * Grants a TV app a permission no app can grant itself (`DUMP`, `WRITE_SECURE_SETTINGS`,
     * `READ_LOGS`). Only ADB can grant it, and the grant survives reboots.
     *
     * This is the only command built from typed text rather than the catalogue, hence two checks:
     * package and permission must be identifiers (a `;` would start a second command on the TV),
     * and the app must declare the permission (`pm grant` would otherwise fail with a Java exception
     * instead of a readable reason).
     */
    suspend fun accorderPermission(
        paquet: String,
        permission: String,
        permissionsDeclarees: Set<String>,
    ): ResultatAction {
        val refus = motifDeRefusPermission(paquet, permission, permissionsDeclarees)
        if (refus != null) return ResultatAction(paquet, permission, false, motif = refus)
        return changerPermission(paquet, permission, accorder = true)
    }

    /** Revokes a granted permission. Revoking is always allowed, so the manifest is not checked. */
    suspend fun retirerPermission(paquet: String, permission: String): ResultatAction {
        val refus = motifDeRefusPermission(paquet, permission, permissionsDeclarees = null)
        if (refus != null) return ResultatAction(paquet, permission, false, motif = refus)
        return changerPermission(paquet, permission, accorder = false)
    }

    private suspend fun changerPermission(
        paquet: String,
        permission: String,
        accorder: Boolean,
    ): ResultatAction {
        val verbe = if (accorder) "grant" else "revoke"
        val inverse = if (accorder) "revoke" else "grant"
        val sortie = executeur.executer("pm $verbe $paquet $permission")

        // `pm grant` prints nothing on success; any output is an exception from the TV.
        val reussi = sortie.reussi && sortie.sortie.isBlank()
        val message = if (reussi) "" else sortie.sortie

        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.PERMISSION,
                cible = "$paquet $permission",
                libelle = "$paquet — ${permission.substringAfterLast('.')}",
                commandeAnnulation = "pm $inverse $paquet $permission",
                reussi = reussi,
                message = message,
            ),
        )
        return ResultatAction(paquet, permission, reussi, message, motifSiMuet(reussi, message))
    }

    /**
     * Sets an app-op mode, Android's second lock next to permissions.
     *
     * Example: `pm grant ... PACKAGE_USAGE_STATS` succeeds, yet the app sees nothing while
     * `GET_USAGE_STATS` stays denied. The reverse holds too, hence [modePrecedent]: undo restores
     * the mode found before, not an assumed `default`.
     */
    suspend fun reglerAppOp(
        paquet: String,
        appOp: String,
        mode: String,
        modePrecedent: String,
    ): ResultatAction {
        val refus = when {
            !IDENTIFIANT.matches(paquet) -> MotifMoteur.NomInvalide(NatureNom.PAQUET, paquet)
            !IDENTIFIANT.matches(appOp) -> MotifMoteur.NomInvalide(NatureNom.APP_OP, appOp)
            mode !in MODES_APP_OP -> MotifMoteur.ModeAppOpInconnu(mode)
            else -> null
        }
        if (refus != null) return ResultatAction(paquet, appOp, false, motif = refus)

        val sortie = executeur.executer("cmd appops set $paquet $appOp $mode")

        // Like `pm grant`, `cmd appops set` prints nothing on success.
        val reussi = sortie.reussi && sortie.sortie.isBlank()
        val message = if (reussi) "" else sortie.sortie
        val retour = modePrecedent.ifBlank { MODE_APP_OP_DEFAUT }

        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.APP_OP,
                cible = "$paquet $appOp",
                libelle = "$paquet — $appOp",
                commandeAnnulation = "cmd appops set $paquet $appOp $retour",
                reussi = reussi,
                message = message,
            ),
        )
        return ResultatAction(paquet, appOp, reussi, message, motifSiMuet(reussi, message))
    }

    private fun motifDeRefusComposant(composant: String): MotifMoteur? =
        if (COMPOSANT.matches(composant)) null else MotifMoteur.NomInvalide(NatureNom.COMPOSANT, composant)

    private fun motifDeRefusPermission(
        paquet: String,
        permission: String,
        permissionsDeclarees: Set<String>?,
    ): MotifMoteur? = when {
        !IDENTIFIANT.matches(paquet) -> MotifMoteur.NomInvalide(NatureNom.PAQUET, paquet)
        !IDENTIFIANT.matches(permission) -> MotifMoteur.NomInvalide(NatureNom.PERMISSION, permission)
        permissionsDeclarees != null && permission !in permissionsDeclarees ->
            MotifMoteur.PermissionNonDemandee(paquet, permission)

        else -> null
    }

    private fun motifDeRefus(
        entree: EntreePaquet,
        catalogue: Catalogue,
        launchersDisponibles: Boolean,
    ): MotifMoteur? = when {
        // The package goes into the shell, so it must be a plain identifier. Catalogue names are; those a phone
        // reports (Catalogue.avecApplicationsDuMenu) come from the device.
        !IDENTIFIANT.matches(entree.paquet) -> MotifMoteur.NomInvalide(NatureNom.PAQUET, entree.paquet)

        catalogue.estProtege(entree.paquet) -> MotifMoteur.Protege(catalogue.motifProtection(entree.paquet).orEmpty())

        entree.requiertLauncherTiers && !launchersDisponibles -> MotifMoteur.SansLauncherTiers

        else -> null
    }

    private companion object {
        /** The TV shell takes the line as is, so only a plain identifier is allowed. */
        val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        /** E.g. `com.spocky.projengmenu/.MainActivity`: two identifiers and a slash, nothing more. */
        val COMPOSANT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.]+""")

        const val MODE_APP_OP_DEFAUT = "default"

        /** The four modes `appops` accepts; anything else is a typo. */
        val MODES_APP_OP = setOf("allow", "deny", "ignore", MODE_APP_OP_DEFAUT)

        /** Label of a home screen journal entry, as written by the Markdown export. */
        const val LIBELLE_ACCUEIL = "Écran d'accueil"
    }
}
