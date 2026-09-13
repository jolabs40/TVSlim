package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.shell.ExecuteurCommande

/** Ce qu'une seule interrogation du téléviseur rapporte. */
data class Photographie(
    val infos: InfosAppareil = InfosAppareil.VIDE,
    val etats: Map<String, EtatPaquet> = emptyMap(),
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
                launchersTiers = launchers(sections[MARQUEUR_LAUNCHERS], paquetsDAccueil),
            ),
            etats = paquetsSurveilles.associateWith { paquet ->
                when (paquet) {
                    in desactives -> EtatPaquet.DESACTIVE
                    in actifs -> EtatPaquet.ACTIF
                    else -> EtatPaquet.ABSENT
                }
            },
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

    private fun decouper(sortie: String): Map<String, List<String>> {
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

    /**
     * Applications capables de servir d'écran d'accueil, hors accueils d'usine du catalogue.
     * Sert au garde-fou : sans launcher tiers, l'accueil d'origine ne doit pas être désactivé.
     */
    private fun launchers(
        lignes: List<String>?,
        paquetsDAccueil: Set<String>,
    ): List<LauncherInstalle> {
        val trouves = mutableListOf<LauncherInstalle>()
        var priorite = 0

        lignes.orEmpty().forEach { ligne ->
            PRIORITE.find(ligne)?.groupValues?.get(1)?.toIntOrNull()?.let {
                priorite = it
                return@forEach
            }
            if (!ligne.contains('/') || ligne.startsWith("Activity Resolver")) return@forEach

            val composant = ligne.substringAfter(' ', ligne).trim()
            val paquet = composant.substringBefore('/')
            if (paquet.isBlank() || paquet in paquetsDAccueil) return@forEach
            if (estUnRepliSysteme(priorite, composant)) return@forEach

            trouves += LauncherInstalle(paquet = paquet, nom = paquet, composant = composant)
        }
        return trouves.distinctBy { it.paquet }
    }

    /**
     * `FallbackHome` répond aussi à `category.HOME`, mais n'affiche qu'un écran vide le temps
     * du démarrage : le prendre pour un écran d'accueil de remplacement laisserait désactiver
     * l'accueil d'usine et démarrer sur du vide. Android le trahit par sa priorité négative.
     */
    private fun estUnRepliSysteme(priorite: Int, composant: String): Boolean =
        priorite < 0 || composant.contains("FallbackHome", ignoreCase = true)

    /** Où l'on se trouve dans la sortie de `dumpsys package`. */
    private enum class SectionPermissions { AUCUNE, DEMANDEES, ACCORDEES }

    internal companion object {
        private val PRIORITE = Regex("""priority=(-?\d+)""")

        /** « android.permission.DUMP » seul, ou suivi de « : granted=true ». */
        private val LIGNE_PERMISSION =
            Regex("""^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)(?::(.*))?$""")

        /** Un nom de paquet, et rien d'autre : la commande part dans un shell. */
        private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

        /** « 161,015K: com.spocky.projengmenu (pid 4799 state 14 oom 150 / activities) » */
        private val PROCESSUS = Regex("""^([\d,]+)K:\s+(\S+)\s+\(pid\s+(\d+)""")
        private val NOMBRE = Regex("""([\d,]+)K""")
        private val CACHE = Regex("""([\d,]+)K cached pss""")

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
        ).joinToString("; ")
    }
}
