package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.shell.ExecuteurCommande

/**
 * Lit l'état d'un téléviseur **à distance**, uniquement par commandes shell — c'est la
 * contrepartie de la lecture locale par `PackageManager` que fait l'application installée sur
 * le téléviseur.
 *
 * Toutes les commandes sont en lecture seule : rien ici ne modifie l'appareil.
 */
class LecteurDistant(private val executeur: ExecuteurCommande) {

    /** Paquets désactivés, tels que les connaît le gestionnaire de paquets du téléviseur. */
    suspend fun paquetsDesactives(): Set<String> = listePaquets("-d")

    suspend fun paquetsActifs(): Set<String> = listePaquets("-e")

    /** État de chaque paquet demandé, en deux commandes seulement. */
    suspend fun etats(paquets: Collection<String>): Map<String, EtatPaquet> {
        val desactives = paquetsDesactives()
        val actifs = paquetsActifs()
        return paquets.associateWith { paquet ->
            when (paquet) {
                in desactives -> EtatPaquet.DESACTIVE
                in actifs -> EtatPaquet.ACTIF
                else -> EtatPaquet.ABSENT
            }
        }
    }

    suspend fun infos(paquetsDAccueil: Set<String>): InfosAppareil {
        val proprietes = proprietes(
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.build.version.release",
            "ro.build.display.id",
        )
        val memoire = memoire()
        val accueil = accueilActuel()
        return InfosAppareil(
            marque = proprietes.getOrElse(0) { "" },
            modele = proprietes.getOrElse(1) { "" },
            versionAndroid = proprietes.getOrElse(2) { "" },
            build = proprietes.getOrElse(3) { "" },
            memoireTotaleMo = memoire.first,
            memoireLibreMo = memoire.second,
            paquetsInstalles = paquetsActifs().size,
            paquetsDesactives = paquetsDesactives().size,
            accueilActuel = accueil,
            launchersTiers = launchersTiers(paquetsDAccueil),
        )
    }

    /**
     * Applications capables de servir d'écran d'accueil, hors accueils d'usine du catalogue.
     * Sert au garde-fou : sans launcher tiers, l'accueil d'origine ne doit pas être désactivé.
     */
    suspend fun launchersTiers(paquetsDAccueil: Set<String>): List<LauncherInstalle> {
        val sortie = executeur.executer(
            "cmd package query-activities --brief " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME",
        )
        if (!sortie.reussi) return emptyList()
        return sortie.sortie.lineSequence()
            .map { it.trim() }
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
            .toList()
    }

    suspend fun accueilActuel(): String {
        val sortie = executeur.executer(
            "cmd package resolve-activity --brief " +
                "-a android.intent.action.MAIN -c android.intent.category.HOME",
        )
        if (!sortie.reussi) return ""
        return sortie.sortie.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') }
            ?.substringBefore('/')
            .orEmpty()
    }

    private suspend fun listePaquets(option: String): Set<String> {
        val sortie = executeur.executer("pm list packages $option")
        if (!sortie.reussi) return emptySet()
        return sortie.sortie.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private suspend fun proprietes(vararg cles: String): List<String> {
        val sortie = executeur.executer(cles.joinToString("; ") { "getprop $it" })
        if (!sortie.reussi) return emptyList()
        return sortie.sortie.lines().map { it.trim() }
    }

    /** Mémoire totale et disponible, en mégaoctets, lues dans /proc/meminfo. */
    private suspend fun memoire(): Pair<Long, Long> {
        val sortie = executeur.executer("cat /proc/meminfo")
        if (!sortie.reussi) return 0L to 0L
        fun ligne(cle: String): Long = sortie.sortie.lineSequence()
            .firstOrNull { it.startsWith(cle) }
            ?.filter { it.isDigit() }
            ?.toLongOrNull()
            ?.div(1024)
            ?: 0L
        return ligne("MemTotal") to ligne("MemAvailable")
    }
}
