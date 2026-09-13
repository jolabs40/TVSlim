package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.shell.ExecuteurCommande

/** Ce qu'une seule interrogation du téléviseur rapporte. */
data class Photographie(
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val etats: Map<String, EtatPaquet> = emptyMap(),
    /**
     * Chaque paquet livré avec l'appareil — tout sauf ce que la personne a installé —, actif ou
     * désactivé. Vide quand la liste des applications tierces manque : sans elle, on ne saurait pas
     * les distinguer, et une application installée passerait pour un paquet du constructeur.
     */
    val paquetsSysteme: Map<String, EtatPaquet> = emptyMap(),
)

/**
 * Lit l'état d'un téléviseur **à distance**, uniquement par commandes shell — c'est la
 * contrepartie de la lecture locale par `PackageManager` que fait l'application installée sur
 * le téléviseur.
 *
 * Tout tient en **une seule commande** : sur une liaison réseau, chaque aller-retour se paie,
 * et la version en six appels relisait deux fois la liste des paquets. Les sections sont
 * séparées par des marqueurs improbables dans une sortie de `pm`.
 *
 * Toutes les commandes sont en lecture seule : rien ici ne modifie l'appareil.
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
        // Null quand la section manque, et non vide : « aucune application tierce » serait faux.
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
        )
    }

    /**
     * Répartition de la mémoire et poids de chaque processus.
     *
     * Séparée de la photographie : `dumpsys meminfo` est nettement plus lourd que le reste, et
     * n'intéresse que l'écran qui l'affiche. Le chiffre retenu est le PSS, la seule mesure qui
     * ne compte pas deux fois la mémoire partagée entre processus.
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

    /**
     * Occupation du stockage interne, et poids de chaque application. À la demande, comme la
     * mémoire : seul l'onglet qui l'affiche en a besoin.
     */
    suspend fun stockage(): RepartitionStockage {
        val sortie = executeur.executer(COMMANDE_STOCKAGE)
        return if (sortie.reussi) LectureStockage.interpreter(sortie.sortie) else RepartitionStockage()
    }

    /**
     * Ce que chaque paquet déclare au système — emplacement, identité, services sensibles, icône —, pour
     * l'inventaire des inconnus : à la demande, comme la mémoire. Le code de sortie n'est que celui de la
     * dernière requête ; chaque section se lit pour elle-même, quoi qu'il vaille.
     */
    suspend fun indices(): Map<String, IndicesPaquet> =
        LectureIndices.interpreter(executeur.executer(LectureIndices.COMMANDE).sortie)

    private fun nombre(brut: String): Long = brut.replace(",", "").trim().toLongOrNull() ?: 0L

    private fun premierNombre(ligne: String): Long =
        NOMBRE.find(ligne)?.let { nombre(it.groupValues[1]) } ?: 0L

    /** Une seule question, très courte : ce paquet est-il installé ? Sert à guetter une pose. */
    suspend fun estInstalle(paquet: String): Boolean {
        val sortie = executeur.executer("pm list packages $paquet")
        return sortie.reussi &&
            sortie.sortie.lineSequence().any { it.trim() == "package:$paquet" }
    }

    /**
     * Permissions qu'une application déclare, et celles qu'elle a effectivement obtenues.
     *
     * Sert avant un `pm grant` : le manifeste fait foi, et une permission qui n'y figure pas se
     * refuse ici, avec une phrase, plutôt que sur le téléviseur, avec une exception Java.
     *
     * `dumpsys package` range les permissions en sections indentées — `requested permissions:`
     * énumère ce que le manifeste demande, `install permissions:` et `runtime permissions:` ce
     * qui est réellement accordé. `declared permissions:` liste au contraire ce que
     * l'application *définit* pour les autres : elle ne nous intéresse pas.
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

            // Un en-tête de section : une ligne qui se termine par « : » sans rien porter
            // d'autre. « User 0: ceDataInode=… » n'en est pas un, et sépare pourtant les
            // permissions d'installation de celles d'exécution.
            if (nette.endsWith(":") && !nette.contains("granted=")) {
                val titre = nette.lowercase()
                section = when {
                    titre.startsWith("requested permissions") -> SectionPermissions.DEMANDEES
                    titre.startsWith("install permissions") ||
                        titre.startsWith("runtime permissions") -> SectionPermissions.ACCORDEES

                    else -> SectionPermissions.AUCUNE
                }
                // Seul un paquet réellement installé porte ces sections : `dumpsys` répond
                // « Unable to find package » et rien d'autre pour les autres.
                if (section != SectionPermissions.AUCUNE) trouve = true
                return@forEach
            }
            if (section == SectionPermissions.AUCUNE) return@forEach

            // Tout ce qui n'est pas un nom de permission est ignoré sans quitter la section :
            // `dumpsys` y glisse des lignes de service, et en sortir trop tôt ferait manquer
            // les permissions suivantes.
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
     * Mode d'un app-op : « allow », « ignore », « deny » ou « default ».
     *
     * Certaines permissions ne suffisent pas à elles seules — `PACKAGE_USAGE_STATS` est aussi
     * gouvernée par l'app-op `GET_USAGE_STATS`. Tant que celui-ci vaut « default », la
     * permission tranche ; posé à « ignore », il la contredit, et l'application ne voit rien
     * malgré un `pm grant` réussi.
     *
     * Trois sorties possibles, toutes rencontrées sur du vrai matériel :
     * « GET_USAGE_STATS: allow; time=… », « No operations. » suivi de « Default mode: default »,
     * ou une ligne « Error: … » quand le paquet ou l'op n'existe pas.
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

    /** Mémoire totale et disponible, en mégaoctets, lues dans /proc/meminfo. */
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

    /** Le composant entier de l'accueil en place : ce que `set-home-activity` saurait rétablir. */
    private fun composantAccueil(lignes: List<String>?): String =
        lignes.orEmpty().lastOrNull { COMPOSANT.matches(it) }.orEmpty()

    /**
     * Applications capables de servir d'écran d'accueil, hors accueils d'usine du catalogue.
     * Sert au garde-fou : sans launcher tiers, l'accueil d'origine ne doit pas être désactivé.
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
     * Les écrans d'accueil livrés avec l'appareil, **désactivés compris** — uniquement pour les
     * montrer : le garde-fou, lui, ne regarde que [launchers].
     *
     * `query-activities` ne rend un paquet désactivé qu'avec `MATCH_DISABLED_COMPONENTS` : relevé sur
     * la TCL, Google TV coupé n'apparaît qu'ainsi. Est « d'usine » ce que la personne n'a pas installé
     * (`pm list packages -3`), hors écrans de repli, assistants de configuration et provisionnement,
     * qui répondent aussi à HOME sans servir d'accueil. Sans la liste des applications tierces, on
     * s'en tient aux accueils que le catalogue connaît.
     */
    private fun accueilsUsine(
        lignes: List<String>?,
        tiers: Set<String>?,
        paquetsDAccueil: Set<String>,
        desactives: Set<String>,
        actifs: Set<String>,
    ): List<AccueilUsine> {
        val trouves = activitesAccueil(lignes)
            // Un Android qui ignore le drapeau répond par son aide, où traînent des « a/b ».
            .filter { COMPOSANT.matches(it.composant) }
            .filterNot { estUnRepliSysteme(it.priorite, it.composant) || estUnAssistant(it.paquet) }
            .filter { activite -> if (tiers == null) activite.paquet in paquetsDAccueil else activite.paquet !in tiers }
            .distinctBy { it.paquet }
            .map { AccueilUsine(it.paquet, it.composant, actif = it.paquet !in desactives) }

        // Faute de réponse, les accueils du catalogue présents sur l'appareil restent au moins nommés.
        val duCatalogue = paquetsDAccueil
            .filter { (it in desactives || it in actifs) && !estUnAssistant(it) }
            .filter { paquet -> trouves.none { it.paquet == paquet } }
            .map { AccueilUsine(it, composant = "", actif = it !in desactives) }

        return trouves + duCatalogue
    }

    /** Chaque composant d'une sortie de `query-activities --brief`, avec la priorité annoncée avant lui. */
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

    /** Assistants de configuration, provisionnement, sélecteur du système : HOME sans être un accueil. */
    private fun estUnAssistant(paquet: String): Boolean =
        paquet == "android" || ASSISTANT.containsMatchIn(paquet)

    /**
     * `FallbackHome` répond aussi à `category.HOME`, mais n'affiche qu'un écran vide le temps
     * du démarrage : le prendre pour un écran d'accueil de remplacement laisserait désactiver
     * l'accueil d'usine et démarrer sur du vide. Android le trahit par sa priorité négative.
     */
    private fun estUnRepliSysteme(priorite: Int, composant: String): Boolean =
        priorite < 0 || composant.contains("FallbackHome", ignoreCase = true)

    /** Où l'on se trouve dans la sortie de `dumpsys package`. */
    private enum class SectionPermissions { AUCUNE, DEMANDEES, ACCORDEES }

    /** Une activité qui répond à `category.HOME`, et la priorité qu'elle y déclare. */
    private data class ActiviteAccueil(val priorite: Int, val composant: String) {
        val paquet: String get() = composant.substringBefore('/')
    }

    internal companion object {
        private val PRIORITE = Regex("""priority=(-?\d+)""")

        /** « paquet/.Activité » : un composant, et rien d'autre — surtout pas une ligne d'aide. */
        private val COMPOSANT = Regex("""[A-Za-z0-9_.]+/[A-Za-z0-9_.]+""")

        private val ASSISTANT = Regex("setup|provision", RegexOption.IGNORE_CASE)

        /** `MATCH_DISABLED_COMPONENTS` : sans lui, un accueil désactivé n'existe plus pour Android. */
        private const val AVEC_DESACTIVES = 0x200

        /** « android.permission.DUMP » seul, ou suivi de « : granted=true ». */
        private val LIGNE_PERMISSION =
            Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)(?::(.*))?$""")

        /** Un nom de paquet, et rien d'autre : la commande part dans un shell. */
        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        /** « 161,015K: com.spocky.projengmenu (pid 4799 state 14 oom 150 / activities) » */
        private val PROCESSUS = Regex("""^([\d,]+)K:\s+(\S+)\s+\(pid\s+(\d+)""")
        private val NOMBRE = Regex("""([\d,]+)K""")
        private val CACHE = Regex("""([\d,]+)K cached pss""")

        /** Range chaque ligne sous le dernier marqueur rencontré, débarrassée de ses blancs ; les lignes vides sautent. */
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

        // Surtout pas de « # » : dans un shell, un mot qui commence par # ouvre un commentaire
        // et avale tout le reste de la ligne — la commande entière se réduisait à un echo vide.
        const val PREFIXE_MARQUEUR = "@@TVSLIM_"
        const val MARQUEUR_DESACTIVES = "@@TVSLIM_D"
        const val MARQUEUR_ACTIFS = "@@TVSLIM_E"
        const val MARQUEUR_PROPRIETES = "@@TVSLIM_P"
        const val MARQUEUR_MEMOIRE = "@@TVSLIM_M"
        const val MARQUEUR_ACCUEIL = "@@TVSLIM_H"
        const val MARQUEUR_LAUNCHERS = "@@TVSLIM_L"

        /**
         * La marque a sa section à elle : une propriété vide n'y laisse qu'une section vide, là où
         * elle décalerait les quatre lignes de [MARQUEUR_PROPRIETES], les lignes blanches étant
         * écartées au découpage.
         */
        const val MARQUEUR_MARQUE = "@@TVSLIM_B"

        /** Les activités d'accueil, désactivées comprises : de quoi retrouver l'accueil d'usine coupé. */
        const val MARQUEUR_ACCUEILS_TOUS = "@@TVSLIM_U"

        /** Les applications installées par la personne : tout le reste est venu avec l'appareil. */
        const val MARQUEUR_TIERS = "@@TVSLIM_T"

        /** `df` après `diskstats` : certains appareils ne donnent pas la ligne « Data-Free ». */
        const val COMMANDE_STOCKAGE = "dumpsys diskstats; echo ${LectureStockage.MARQUEUR_DF}; df -k /data"

        val COMMANDE = listOf(
            "echo $MARQUEUR_DESACTIVES",
            "pm list packages -d",
            "echo $MARQUEUR_ACTIFS",
            "pm list packages -e",
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
            "cmd package resolve-activity --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME",
            "echo $MARQUEUR_LAUNCHERS",
            "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.HOME",
            "echo $MARQUEUR_ACCUEILS_TOUS",
            "cmd package query-activities --brief --query-flags $AVEC_DESACTIVES " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME",
            "echo $MARQUEUR_TIERS",
            "pm list packages -3",
        ).joinToString("; ")
    }
}
