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

    internal companion object {
        private val PRIORITE = Regex("""priority=(-?\d+)""")

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
