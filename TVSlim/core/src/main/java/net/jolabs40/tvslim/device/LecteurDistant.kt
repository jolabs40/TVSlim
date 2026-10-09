package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.shell.ExecuteurCommande

/** Result of a single query of the TV. */
data class Photographie(
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val etats: Map<String, EtatPaquet> = emptyMap(),
    /**
     * Every package shipped with the device (all but user-installed ones), enabled or disabled. Empty when
     * the third-party list is missing: without it, an installed app would pass for a manufacturer package.
     */
    val paquetsSysteme: Map<String, EtatPaquet> = emptyMap(),
    /** Packages with an icon in a phone's app menu (`category.LAUNCHER`), disabled ones included. */
    val applicationsMenu: Set<String> = emptySet(),
)

/**
 * Reads a TV's state over the shell, read-only (the TV app reads it locally through `PackageManager`).
 * The snapshot is a single command to save network round trips, split into sections by `@@TVSLIM_` markers.
 */
class LecteurDistant(private val executeur: ExecuteurCommande) {

    suspend fun photographie(
        paquetsSurveilles: Collection<String>,
        paquetsDAccueil: Set<String>,
    ): Photographie {
        val sortie = executeur.executer(COMMANDE)
        if (!sortie.reussi) return Photographie()

        val sections = decouper(sortie.sortie)
        val desactives = paquets(sections[MARQUEUR_DESACTIVES])
        val actifs = paquets(sections[MARQUEUR_ACTIFS])
        val proprietes = sections[MARQUEUR_PROPRIETES].orEmpty().map { it.trim() }
        val memoire = memoire(sections[MARQUEUR_MEMOIRE])
        // Null rather than empty when the section is missing: "no third-party app" would be wrong.
        val tiers = sections[MARQUEUR_TIERS]?.let(::paquets)

        return Photographie(
            infos = InfosAppareil(
                marque = proprietes.getOrElse(0) { "" },
                marqueCommerciale = sections[MARQUEUR_MARQUE].orEmpty().firstOrNull()?.trim().orEmpty(),
                modele = proprietes.getOrElse(1) { "" },
                versionAndroid = proprietes.getOrElse(2) { "" },
                build = proprietes.getOrElse(3) { "" },
                memoireTotaleMo = memoire.first,
                memoireLibreMo = memoire.second,
                paquetsInstalles = actifs.size,
                paquetsDesactives = desactives.size,
                accueilActuel = accueil(sections[MARQUEUR_ACCUEIL]),
                composantAccueil = composantAccueil(sections[MARQUEUR_ACCUEIL]),
                launchersTiers = launchers(sections[MARQUEUR_LAUNCHERS], paquetsDAccueil),
                accueilsUsine = accueilsUsine(
                    lignes = sections[MARQUEUR_ACCUEILS_TOUS],
                    tiers = tiers,
                    paquetsDAccueil = paquetsDAccueil,
                    desactives = desactives,
                    actifs = actifs,
                ),
                caracteristiques = sections[MARQUEUR_CARACTERISTIQUES].orEmpty().firstOrNull()?.trim().orEmpty(),
                // Null when the section is missing: "no declared feature" would mean "no touchscreen".
                fonctions = sections[MARQUEUR_FONCTIONS]?.let(::fonctions),
            ),
            etats = paquetsSurveilles.associateWith { paquet ->
                when (paquet) {
                    in desactives -> EtatPaquet.DESACTIVE
                    in actifs -> EtatPaquet.ACTIF
                    else -> EtatPaquet.ABSENT
                }
            },
            paquetsSysteme = tiers?.let { installes ->
                (actifs.associateWith { EtatPaquet.ACTIF } + desactives.associateWith { EtatPaquet.DESACTIVE })
                    .filterKeys { it !in installes }
            }.orEmpty(),
            applicationsMenu = applicationsMenu(sections[MARQUEUR_MENU].orEmpty()),
        )
    }

    /**
     * Memory breakdown and per-process usage, kept out of the snapshot because `dumpsys meminfo` is heavy.
     * Uses PSS, the only measure that does not count shared memory twice.
     */
    suspend fun memoire(): RepartitionMemoire {
        val sortie = executeur.executer("dumpsys meminfo")
        if (!sortie.reussi) return RepartitionMemoire()

        val processus = mutableListOf<ProcessusMemoire>()
        var dansLaListe = false
        var total = 0L
        var libre = 0L
        var utilisee = 0L
        var cache = 0L
        var zram = 0L

        sortie.sortie.lineSequence().forEach { ligne ->
            val nette = ligne.trim()
            when {
                nette.startsWith("Total PSS by process") -> dansLaListe = true
                nette.startsWith("Total PSS by") -> dansLaListe = false
                nette.startsWith("Total RAM:") -> total = premierNombre(nette)
                nette.startsWith("Free RAM:") -> {
                    libre = premierNombre(nette)
                    cache = CACHE.find(nette)?.let { nombre(it.groupValues[1]) } ?: 0L
                }
                nette.startsWith("Used RAM:") -> utilisee = premierNombre(nette)
                nette.startsWith("ZRAM:") -> zram = premierNombre(nette)
                dansLaListe -> PROCESSUS.find(nette)?.let { trouve ->
                    processus += ProcessusMemoire(
                        nom = trouve.groupValues[2],
                        pid = trouve.groupValues[3].toIntOrNull() ?: 0,
                        kilooctets = nombre(trouve.groupValues[1]),
                    )
                }
            }
        }

        return RepartitionMemoire(
            totalKo = total,
            libreKo = libre,
            utiliseeKo = utilisee,
            cacheKo = cache,
            zramKo = zram,
            processus = processus.sortedByDescending { it.kilooctets },
        )
    }

    /** Internal storage usage and per-app size, on demand like [memoire]. */
    suspend fun stockage(): RepartitionStockage {
        val sortie = executeur.executer(COMMANDE_STOCKAGE)
        return if (sortie.reussi) LectureStockage.interpreter(sortie.sortie) else RepartitionStockage()
    }

    /**
     * Per-package indices for the unknown-packages inventory. The exit code is only the last query's, so
     * sections are parsed regardless of it.
     */
    suspend fun indices(): Map<String, IndicesPaquet> =
        LectureIndices.interpreter(executeur.executer(LectureIndices.COMMANDE).sortie)

    /** Firmware identity for the unknown-packages inventory. */
    suspend fun firmware(): Firmware {
        val sortie = executeur.executer(LectureFirmware.COMMANDE)
        return if (sortie.reussi) LectureFirmware.interpreter(sortie.sortie) else Firmware()
    }

    private fun nombre(brut: String): Long = brut.replace(",", "").trim().toLongOrNull() ?: 0L

    private fun premierNombre(ligne: String): Long =
        NOMBRE.find(ligne)?.let { nombre(it.groupValues[1]) } ?: 0L

    /**
     * Current home package, read after `set-home-activity` to check it took effect: while the factory home
     * is enabled, Android answers `Success` and changes nothing. Empty on failure.
     */
    suspend fun accueilActuel(): String {
        val sortie = executeur.executer(COMMANDE_ACCUEIL)
        return if (sortie.reussi) accueil(sortie.sortie.lines()) else ""
    }

    /** Cheap installed check, polled while waiting for an install. */
    suspend fun estInstalle(paquet: String): Boolean {
        val sortie = executeur.executer("pm list packages --user 0 $paquet")
        return sortie.reussi &&
            sortie.sortie.lineSequence().any { it.trim() == "package:$paquet" }
    }

    /**
     * Permissions an app requests and those it was granted, checked before `pm grant` so that a permission
     * missing from the manifest is rejected here with a message rather than on the TV with a Java exception.
     *
     * Parses the indented `dumpsys package` sections: `requested permissions:` (the manifest),
     * `install permissions:` and `runtime permissions:` (granted). `declared permissions:`, defined for other
     * apps, is ignored.
     */
    suspend fun permissions(paquet: String): PermissionsPaquet {
        if (!IDENTIFIANT.matches(paquet)) return PermissionsPaquet()
        val sortie = executeur.executer("dumpsys package $paquet")
        if (!sortie.reussi) return PermissionsPaquet()

        val demandees = mutableSetOf<String>()
        val accordees = mutableSetOf<String>()
        var trouve = false
        var section = SectionPermissions.AUCUNE

        sortie.sortie.lineSequence().forEach { ligne ->
            val nette = ligne.trim()

            // Section header: a line ending in ":" with nothing else on it. "User 0: ceDataInode=..."
            // is not one, even though it separates install permissions from runtime ones.
            if (nette.endsWith(":") && !nette.contains("granted=")) {
                val titre = nette.lowercase()
                section = when {
                    titre.startsWith("requested permissions") -> SectionPermissions.DEMANDEES
                    titre.startsWith("install permissions") ||
                        titre.startsWith("runtime permissions") -> SectionPermissions.ACCORDEES

                    else -> SectionPermissions.AUCUNE
                }
                // Only an installed package has these sections; for any other, `dumpsys` prints
                // "Unable to find package" and nothing else.
                if (section != SectionPermissions.AUCUNE) trouve = true
                return@forEach
            }
            if (section == SectionPermissions.AUCUNE) return@forEach

            // Skip anything that is not a permission name without leaving the section: `dumpsys`
            // inserts service lines there, and leaving early would miss the permissions after them.
            val trouvee = LIGNE_PERMISSION.find(nette) ?: return@forEach
            val nom = trouvee.groupValues[1]
            if (section == SectionPermissions.DEMANDEES) {
                demandees += nom
            } else if (trouvee.groupValues[2].contains("granted=true")) {
                accordees += nom
            }
        }

        return PermissionsPaquet(paquetTrouve = trouve, demandees = demandees, accordees = accordees)
    }

    /**
     * Mode of an app-op: `allow`, `ignore`, `deny` or `default`.
     *
     * `PACKAGE_USAGE_STATS` is also governed by the `GET_USAGE_STATS` app-op: at `default` the permission
     * decides, at `ignore` the app sees nothing despite a successful `pm grant`.
     *
     * Outputs seen on real hardware: `GET_USAGE_STATS: allow; time=...`, `No operations.` then
     * `Default mode: default`, or `Error: ...` when the package or op does not exist.
     */
    suspend fun modeAppOp(paquet: String, appOp: String): String {
        if (!IDENTIFIANT.matches(paquet) || !IDENTIFIANT.matches(appOp)) return ""
        val sortie = executeur.executer("cmd appops get $paquet $appOp")
        if (!sortie.reussi) return ""

        var defaut = ""
        sortie.sortie.lineSequence().forEach { ligne ->
            val nette = ligne.trim()
            when {
                nette.startsWith("Error:") -> return ""
                nette.startsWith("$appOp:") ->
                    return nette.substringAfter(':').substringBefore(';').trim()

                nette.startsWith("Default mode:") -> defaut = nette.substringAfter(':').trim()
            }
        }
        return defaut
    }

    private fun paquets(lignes: List<String>?): Set<String> =
        lignes.orEmpty().map { it.removePrefix("package:") }.filter { it.isNotBlank() }.toSet()

    /** Total and available memory in MB, from /proc/meminfo. */
    private fun memoire(lignes: List<String>?): Pair<Long, Long> {
        fun valeur(cle: String): Long = lignes.orEmpty()
            .firstOrNull { it.startsWith(cle) }
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?.div(1024)
            ?: 0L
        return valeur("MemTotal") to valeur("MemAvailable")
    }

    private fun accueil(lignes: List<String>?): String =
        lignes.orEmpty().lastOrNull { it.contains('/') }?.substringBefore('/').orEmpty()

    /** Full component of the current home, as `set-home-activity` needs it to restore it. */
    private fun composantAccueil(lignes: List<String>?): String =
        lignes.orEmpty().lastOrNull { COMPOSANT.matches(it) }.orEmpty()

    /**
     * Apps that can act as home screen, excluding the catalogue's factory homes. Used by the safeguard:
     * without a third-party launcher, the stock home must not be disabled.
     */
    private fun launchers(
        lignes: List<String>?,
        paquetsDAccueil: Set<String>,
    ): List<LauncherInstalle> =
        activitesAccueil(lignes)
            .filterNot { it.paquet.isBlank() || it.paquet in paquetsDAccueil }
            .filterNot { estUnRepliSysteme(it.priorite, it.composant) }
            .map { LauncherInstalle(paquet = it.paquet, nom = it.paquet, composant = it.composant) }
            .distinctBy { it.paquet }

    /**
     * Factory home screens, disabled ones included, for display only (the safeguard uses [launchers]).
     *
     * `query-activities` returns a disabled package only with `MATCH_DISABLED_COMPONENTS`, as seen on the TCL
     * with Google TV disabled. "Factory" means absent from `pm list packages -3`, minus fallback screens, setup
     * wizards and provisioning, which also answer HOME. Without the third-party list, only the catalogue's
     * homes are kept.
     */
    private fun accueilsUsine(
        lignes: List<String>?,
        tiers: Set<String>?,
        paquetsDAccueil: Set<String>,
        desactives: Set<String>,
        actifs: Set<String>,
    ): List<AccueilUsine> {
        val trouves = activitesAccueil(lignes)
            // An Android that ignores the flag prints its help text, which contains "a/b" strings.
            .filter { COMPOSANT.matches(it.composant) }
            .filterNot { estUnRepliSysteme(it.priorite, it.composant) || estUnAssistant(it.paquet) }
            .filter { activite -> if (tiers == null) activite.paquet in paquetsDAccueil else activite.paquet !in tiers }
            .distinctBy { it.paquet }
            .map { AccueilUsine(it.paquet, it.composant, actif = it.paquet !in desactives) }

        // Without an answer, the catalogue's homes present on the device are still listed by name.
        val duCatalogue = paquetsDAccueil
            .filter { (it in desactives || it in actifs) && !estUnAssistant(it) }
            .filter { paquet -> trouves.none { it.paquet == paquet } }
            .map { AccueilUsine(it, composant = "", actif = it !in desactives) }

        return trouves + duCatalogue
    }

    /** Each component in a `query-activities --brief` output, with the priority printed before it. */
    private fun activitesAccueil(lignes: List<String>?): List<ActiviteAccueil> {
        val trouvees = mutableListOf<ActiviteAccueil>()
        var priorite = 0

        lignes.orEmpty().forEach { ligne ->
            PRIORITE.find(ligne)?.groupValues?.get(1)?.toIntOrNull()?.let {
                priorite = it
                return@forEach
            }
            if (!ligne.contains('/') || ligne.startsWith("Activity Resolver")) return@forEach
            trouvees += ActiviteAccueil(priorite, ligne.substringAfter(' ', ligne).trim())
        }
        return trouvees
    }

    /**
     * Setup wizards, provisioning, the system chooser and manufacturer dispatchers handle HOME without being
     * a home screen. On Philips, `org.droidtv.homeintentresolver` takes HOME at priority 100 and routes the
     * key elsewhere.
     */
    private fun estUnAssistant(paquet: String): Boolean =
        paquet == "android" || ASSISTANT.containsMatchIn(paquet)

    /**
     * `FallbackHome` answers `category.HOME` but only shows a blank screen during boot; treating it as a
     * replacement home would allow disabling the stock home and booting into nothing. Android gives it a
     * negative priority.
     */
    private fun estUnRepliSysteme(priorite: Int, composant: String): Boolean =
        priorite < 0 || composant.contains("FallbackHome", ignoreCase = true)

    private enum class SectionPermissions { AUCUNE, DEMANDEES, ACCORDEES }

    private data class ActiviteAccueil(val priorite: Int, val composant: String) {
        val paquet: String get() = composant.substringBefore('/')
    }

    internal companion object {
        private val PRIORITE = Regex("""priority=(-?\d+)""")

        /** `package/.Activity`: a component and nothing else, in particular not a help line. */
        private val COMPOSANT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.]+""")

        private val ASSISTANT = Regex("setup|provision|intentresolver", RegexOption.IGNORE_CASE)

        /** `MATCH_DISABLED_COMPONENTS`: without it, a disabled home does not exist for Android. */
        private const val AVEC_DESACTIVES = 0x200

        /** `android.permission.DUMP` alone, or followed by `: granted=true`. */
        private val LIGNE_PERMISSION =
            Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)(?::(.*))?$""")

        /** A package name and nothing else: the command goes to a shell. */
        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        /** `161,015K: com.spocky.projengmenu (pid 4799 state 14 oom 150 / activities)` */
        private val PROCESSUS = Regex("""^([\d,]+)K:\s+(\S+)\s+\(pid\s+(\d+)""")
        private val NOMBRE = Regex("""([\d,]+)K""")
        private val CACHE = Regex("""([\d,]+)K cached pss""")

        /** Files each trimmed line under the last marker seen; blank lines are dropped. */
        internal fun decouper(sortie: String): Map<String, List<String>> {
            val sections = mutableMapOf<String, MutableList<String>>()
            var courante: MutableList<String>? = null
            sortie.lineSequence().forEach { ligne ->
                val nette = ligne.trim()
                if (nette.startsWith(PREFIXE_MARQUEUR)) {
                    courante = mutableListOf<String>().also { sections[nette] = it }
                } else if (nette.isNotBlank()) {
                    courante?.add(nette)
                }
            }
            return sections
        }

        // Never `#`: a shell word starting with # comments out the rest of the line, i.e. the whole command.
        const val PREFIXE_MARQUEUR = "@@TVSLIM_"
        const val MARQUEUR_DESACTIVES = "@@TVSLIM_D"
        const val MARQUEUR_ACTIFS = "@@TVSLIM_E"
        const val MARQUEUR_PROPRIETES = "@@TVSLIM_P"
        const val MARQUEUR_MEMOIRE = "@@TVSLIM_M"
        const val MARQUEUR_ACCUEIL = "@@TVSLIM_H"
        const val MARQUEUR_LAUNCHERS = "@@TVSLIM_L"

        /**
         * Own section for the brand: an empty value in [MARQUEUR_PROPRIETES] would shift its four lines, since
         * blank lines are dropped.
         */
        const val MARQUEUR_MARQUE = "@@TVSLIM_B"

        /** Home activities, disabled ones included, to find a disabled factory home. */
        const val MARQUEUR_ACCUEILS_TOUS = "@@TVSLIM_U"

        /** Apps installed by the user; everything else came with the device. */
        const val MARQUEUR_TIERS = "@@TVSLIM_T"

        /** Declared device kind, see [InfosAppareil.typeAppareil]. Own section so an empty value shifts nothing. */
        const val MARQUEUR_CARACTERISTIQUES = "@@TVSLIM_C"

        /**
         * A phone's menu apps, disabled ones included so they stay listed and can be re-enabled. See
         * `Catalogue.avecApplicationsDuMenu`.
         */
        const val MARQUEUR_MENU = "@@TVSLIM_A"

        /** `com.google.android.youtube/com.google.android.apps.youtube.app.WatchWhileActivity` -> the package. */
        internal fun applicationsMenu(lignes: List<String>): Set<String> = lignes
            .filter { '/' in it && !it.startsWith("priority") }
            .map { it.substringBefore('/').trim() }
            .filter { it.isNotEmpty() && ' ' !in it }
            .toSet()
        const val MARQUEUR_FONCTIONS = "@@TVSLIM_F"

        /** `feature:android.software.leanback`, `feature:android.hardware.touchscreen=1` -> the bare name. */
        internal fun fonctions(lignes: List<String>): Set<String> = lignes
            .map { it.removePrefix("feature:").substringBefore('=').trim() }
            .filter { it in InfosAppareil.FONCTIONS_LUES }
            .toSet()

        const val COMMANDE_ACCUEIL =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

        /** `df` after `diskstats`: some devices omit the `Data-Free` line. */
        const val COMMANDE_STOCKAGE = "dumpsys diskstats; echo ${LectureStockage.MARQUEUR_DF}; df -k /data"

        // `--user 0` everywhere: without it, `pm list packages -e` reports a package as enabled if it is
        // enabled in any profile. The TCL has a second, never-opened profile (`new_user`, id 10) where
        // packages disabled for the main profile stay enabled. `pm disable-user` already targets user 0.
        val COMMANDE = listOf(
            "echo $MARQUEUR_DESACTIVES",
            "pm list packages -d --user 0",
            "echo $MARQUEUR_ACTIFS",
            "pm list packages -e --user 0",
            "echo $MARQUEUR_PROPRIETES",
            "getprop ro.product.manufacturer",
            "getprop ro.product.model",
            "getprop ro.build.version.release",
            "getprop ro.build.display.id",
            "echo $MARQUEUR_MARQUE",
            "getprop ro.product.brand",
            "echo $MARQUEUR_MEMOIRE",
            "grep -E 'MemTotal|MemAvailable' /proc/meminfo",
            "echo $MARQUEUR_ACCUEIL",
            COMMANDE_ACCUEIL,
            "echo $MARQUEUR_LAUNCHERS",
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME",
            "echo $MARQUEUR_ACCUEILS_TOUS",
            "cmd package query-activities --brief --query-flags $AVEC_DESACTIVES " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME",
            "echo $MARQUEUR_CARACTERISTIQUES",
            "getprop ro.build.characteristics",
            "echo $MARQUEUR_FONCTIONS",
            "pm list features",
            "echo $MARQUEUR_MENU",
            "cmd package query-activities --brief --query-flags $AVEC_DESACTIVES " +
                "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER",
            // Last, so its exit code is the exit code of the whole command.
            "echo $MARQUEUR_TIERS",
            "pm list packages -3 --user 0",
        ).joinToString("; ")
    }
}
