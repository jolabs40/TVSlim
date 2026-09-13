package net.jolabs40.tvslim.device

/** État d'un paquet du catalogue sur un téléviseur donné. */
enum class EtatPaquet { ABSENT, ACTIF, DESACTIVE }

/** Photographie d'un téléviseur, affichée avant et après une intervention. */
data class InfosAppareil(
    val marque: String = "",
    /**
     * La marque vendue (`ro.product.brand`), quand elle diffère du fabricant : un même assembleur
     * fabrique pour plusieurs enseignes.
     */
    val marqueCommerciale: String = "",
    val modele: String = "",
    val versionAndroid: String = "",
    val build: String = "",
    val memoireTotaleMo: Long = 0,
    val memoireLibreMo: Long = 0,
    val paquetsInstalles: Int = 0,
    val paquetsDesactives: Int = 0,
    val accueilActuel: String = "",
    val launchersTiers: List<LauncherInstalle> = emptyList(),
) {
    /** Le fabricant reconnu, marque vendue d'abord : voir [Fabricant]. */
    val fabricant: Fabricant? get() = Fabricant.identifier(marqueCommerciale, marque)

    /** Téléviseur ou box. Un appareil inconnu est présumé téléviseur : c'est le cas courant. */
    val typeAppareil: TypeAppareil get() = fabricant?.typePour(modele) ?: TypeAppareil.TELEVISEUR

    /** Le nom à montrer et à retenir : la marque vendue plutôt que le sous-traitant (« TPV »). */
    val nomAffiche: String get() = "${fabricant?.nom ?: marque} $modele".trim()

    companion object {
        val VIDE = InfosAppareil()
    }
}

/** Un processus vivant et ce qu'il occupe réellement en mémoire (PSS). */
data class ProcessusMemoire(
    val nom: String,
    val pid: Int,
    val kilooctets: Long,
) {
    val megaoctets: Long get() = kilooctets / 1024

    /** Le paquet derrière le processus : « com.android.vending:background » en cache un. */
    val paquet: String get() = nom.substringBefore(':')

    /**
     * Un processus du système ne porte pas de nom de paquet : `surfaceflinger`, `system`,
     * `vendor.nvidia…`. On ne propose pas de les arrêter — au mieux ils redémarrent aussitôt,
     * au pire l'appareil bronche.
     */
    val estUneApplication: Boolean
        get() = paquet.count { it == '.' } >= 2 && !paquet.startsWith("vendor.")
}

/** Répartition de la mémoire, telle que la voit `dumpsys meminfo`. */
data class RepartitionMemoire(
    val totalKo: Long = 0,
    val libreKo: Long = 0,
    val utiliseeKo: Long = 0,
    val cacheKo: Long = 0,
    val zramKo: Long = 0,
    val processus: List<ProcessusMemoire> = emptyList(),
) {
    val renseignee: Boolean get() = totalKo > 0
}

data class LauncherInstalle(
    val paquet: String,
    val nom: String,
    val composant: String,
)

/**
 * Ce qu'une application déclare vouloir, et ce qu'elle a réellement obtenu.
 *
 * Une permission absente de [demandees] ne s'accorde pas : le manifeste fait foi, et `pm grant`
 * la refuserait de toute façon — mais par une exception Java, là où une phrase est plus utile.
 */
data class PermissionsPaquet(
    val paquetTrouve: Boolean = false,
    val demandees: Set<String> = emptySet(),
    val accordees: Set<String> = emptySet(),
) {
    fun estAccordee(permission: String): Boolean = permission in accordees

    fun estDeclaree(permission: String): Boolean = permission in demandees
}
