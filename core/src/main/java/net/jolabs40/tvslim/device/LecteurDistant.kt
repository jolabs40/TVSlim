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
    ): List<LauncherInstalle> = lignes.orEmpty()
        .filter { it.contains('/') && !it.startsWith("Activity Resolver") }
        .mapNotNull { ligne ->
            val composant = ligne.substringAfter(' ', ligne).trim()
            val paquet = composant.substringBefore('/')
            if (paquet.isBlank() || paquet in paquetsDAccueil) {
                null
            } else {
                LauncherInstalle(paquet = paquet, nom = paquet, composant = composant)
            }
        }
        .distinctBy { it.paquet }

    private companion object {
        const val PREFIXE_MARQUEUR = "###TVSLIM_"
        const val MARQUEUR_DESACTIVES = "###TVSLIM_D"
        const val MARQUEUR_ACTIFS = "###TVSLIM_E"
        const val MARQUEUR_PROPRIETES = "###TVSLIM_P"
        const val MARQUEUR_MEMOIRE = "###TVSLIM_M"
        const val MARQUEUR_ACCUEIL = "###TVSLIM_H"
        const val MARQUEUR_LAUNCHERS = "###TVSLIM_L"

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
