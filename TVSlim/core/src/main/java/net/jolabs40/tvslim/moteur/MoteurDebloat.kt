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
    /** Ce que le téléviseur a répondu, tel quel. */
    val message: String = "",
    /** Ce que le moteur en dit, à rédiger par l'application ; quand il est là, il passe avant [message]. */
    val motif: MotifMoteur? = null,
)

/**
 * Applique et annule les désactivations, avec les garde-fous appris lors de l'intervention
 * manuelle sur la TCL :
 *
 *  - jamais de `pm uninstall` : uniquement `pm disable-user --user 0`, annulé par `pm enable` ;
 *  - refus catégorique des paquets de la liste noire du catalogue ;
 *  - refus de toucher à l'écran d'accueil d'usine tant qu'aucun launcher tiers n'est installé ;
 *  - respect de l'ordre déclaré, pour que `setupwraith` tombe avant `launcherx` — sans quoi
 *    la RecoveryActivity de priorité 1 prendrait la main à la place du launcher choisi.
 *
 * Le moteur ignore par quel canal les commandes partent : service local ou connexion ADB. Il ne
 * rédige rien non plus : ses refus sont des [MotifMoteur], que chaque application met en mots.
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
     * Désigne un écran d'accueil. À n'appeler qu'une fois l'accueil d'usine désactivé : tant
     * qu'il est actif, la commande répond `Success` sans le moindre effet.
     *
     * Le résultat porte le composant pour nom : c'est ce qu'un bilan d'échecs peut citer sans traduction.
     */
    suspend fun definirAccueil(composant: String, ancienAccueil: String): ResultatAction {
        // Les deux partent dans une commande : celui d'annulation aussi, et il sera rejoué tel
        // quel depuis le journal, longtemps après. Ils viennent d'une sortie de `cmd package`,
        // donc d'une source contrainte — mais c'est l'asymétrie qui se paie à la relecture.
        val refus = motifDeRefusComposant(composant) ?: motifDeRefusComposant(ancienAccueil)
        if (refus != null) return ResultatAction(composant, composant, false, motif = refus)

        val sortie = executeur.executer("cmd package set-home-activity $composant")
        journal.ajouter(
            ActionJournal(
                horodatage = System.currentTimeMillis(),
                type = TypeAction.ACCUEIL,
                cible = composant,
                // Les écrans du journal nomment ce type eux-mêmes ; ce libellé ne sert qu'à l'export.
                libelle = LIBELLE_ACCUEIL,
                commandeAnnulation = "cmd package set-home-activity $ancienAccueil",
                reussi = sortie.reussi,
                message = if (sortie.reussi) "" else sortie.sortie,
            ),
        )
        return ResultatAction(composant, composant, sortie.reussi, sortie.sortie)
    }

    /**
     * Ouvre la fiche d'une application dans la boutique **du téléviseur**, à charge pour la
     * personne devant l'écran de valider l'installation à la télécommande.
     *
     * Pour un launcher recommandé, c'est le chemin retenu : rien n'est téléchargé, l'installation
     * vient de la boutique officielle. Un APK que la personne a sous la main passe, lui, par
     * `InstallationApk`, et seulement à sa demande.
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
     * Arrête les processus d'une application. Rien à journaliser : ce n'est pas un changement
     * d'état mais une remise à zéro — l'application repart dès qu'on l'ouvre, ou dès qu'un
     * service la rappelle.
     */
    suspend fun forcerArret(paquet: String): ResultatAction {
        // Ce nom-ci sort d'une expression régulière appliquée à `dumpsys meminfo` : le seul du
        // moteur qui ne vienne ni du catalogue ni d'une liste de paquets.
        if (!IDENTIFIANT.matches(paquet)) {
            return ResultatAction(paquet, paquet, false, motif = MotifMoteur.NomInvalide(NatureNom.PAQUET, paquet))
        }
        val sortie = executeur.executer("am force-stop $paquet")
        return ResultatAction(paquet, paquet, sortie.reussi, sortie.sortie, motifSiMuet(sortie.reussi, sortie.sortie))
    }

    /** Applique une valeur de réglage système et journalise son annulation. */
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
     * Accorde à une application du téléviseur une permission qu'aucune application ne peut
     * s'attribuer seule — `DUMP`, `WRITE_SECURE_SETTINGS`, `READ_LOGS`. Elle ne s'obtient que
     * d'une session ADB, et l'octroi survit aux redémarrages.
     *
     * Deux garde-fous, parce que c'est le seul endroit où une commande se construit à partir
     * d'un texte saisi plutôt que du catalogue :
     *
     *  - le paquet et la permission doivent être des identifiants. Sans cela, une saisie
     *    contenant `;` ouvrirait une seconde commande sur le téléviseur ;
     *  - la permission doit figurer parmi celles que l'application déclare. `pm grant` la
     *    refuserait de toute façon, mais par une exception Java là où une phrase est plus utile.
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

    /** Retire une permission accordée. Rendre est toujours licite : rien à vérifier au manifeste. */
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

        // `pm grant` se tait quand il réussit : toute sortie est une exception du téléviseur.
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
     * Pose le mode d'un app-op — le second verrou d'Android, à côté des permissions.
     *
     * `PACKAGE_USAGE_STATS` en est l'exemple : le `pm grant` réussit, et l'application ne voit
     * pourtant rien tant que `GET_USAGE_STATS` reste refusé. L'inverse est vrai aussi, d'où
     * [modePrecedent] : l'annulation remet le mode trouvé avant, pas un « default » supposé.
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

        // Comme `pm grant`, `cmd appops set` se tait quand il réussit.
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
        // Le paquet part dans le shell : un nom, et rien d'autre. Ceux du catalogue le sont ; ceux qu'un téléphone
        // annonce (Catalogue.avecApplicationsDuMenu) viennent de l'appareil.
        !IDENTIFIANT.matches(entree.paquet) -> MotifMoteur.NomInvalide(NatureNom.PAQUET, entree.paquet)

        catalogue.estProtege(entree.paquet) -> MotifMoteur.Protege(catalogue.motifProtection(entree.paquet).orEmpty())

        entree.requiertLauncherTiers && !launchersDisponibles -> MotifMoteur.SansLauncherTiers

        else -> null
    }

    private companion object {
        /** Le shell du téléviseur prend la ligne telle quelle : un nom, et rien d'autre. */
        val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        /** « com.spocky.projengmenu/.MainActivity » : deux identifiants, une barre, rien de plus. */
        val COMPOSANT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.]+""")

        const val MODE_APP_OP_DEFAUT = "default"

        /** Les quatre modes qu'`appops` accepte. Tout le reste est une faute de frappe. */
        val MODES_APP_OP = setOf("allow", "deny", "ignore", MODE_APP_OP_DEFAUT)

        /** Le libellé d'une ligne d'accueil au journal, tel que l'export Markdown l'écrit. */
        const val LIBELLE_ACCUEIL = "Écran d'accueil"
    }
}
