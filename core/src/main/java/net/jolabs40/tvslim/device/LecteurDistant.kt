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
