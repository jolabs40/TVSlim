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
    /** Le composant entier de l'accueil en place (« paquet/.Activité ») : ce qu'on saurait rétablir. */
    val composantAccueil: String = "",
    val launchersTiers: List<LauncherInstalle> = emptyList(),
    /**
     * Les écrans d'accueil livrés avec l'appareil — Google TV, l'accueil Android TV, celui du
     * constructeur —, **désactivés compris** : c'est justement une fois coupés qu'il faut les retrouver.
     */
    val accueilsUsine: List<AccueilUsine> = emptyList(),
    /** `ro.build.characteristics` : « tv » sur la TCL, « nosdcard » sur un Pixel, « tablet » sur une tablette. */
    val caracteristiques: String = "",
    /**
     * Les fonctions déclarées qui disent le genre d'appareil — [FONCTIONS_LUES] —, sans le préfixe `feature:`.
     * `null` tant qu'elles n'ont pas été lues ; vide, l'appareil n'en déclare aucune — pas même d'écran tactile.
     */
    val fonctions: Set<String>? = null,
) {
    /** Le fabricant reconnu, marque vendue d'abord : voir [Fabricant]. */
    val fabricant: Fabricant? get() = Fabricant.identifier(marqueCommerciale, marque)

    /**
     * Ce que l'appareil **déclare** l'emporte sur sa marque : Google fait des box (Chromecast) et des téléphones
     * (Pixel), et un Pixel passait pour une box. Est un téléviseur ce qui porte `leanback` — Google l'exige de tout
     * Android TV —, `type.television`, la fonction Fire TV, la caractéristique « tv », ou n'a pas d'écran tactile ;
     * la marque départage alors téléviseur et box. Le reste est un téléphone, ou une tablette si l'appareil le dit.
     *
     * Rien de lu — l'appareil n'a pas encore répondu —, la marque décide seule, et un inconnu est présumé
     * téléviseur : c'est le cas courant.
     */
    val typeAppareil: TypeAppareil
        get() {
            val parMarque = fabricant?.typePour(modele) ?: TypeAppareil.TELEVISEUR
            val lues = fonctions
            if (lues == null && caracteristiques.isBlank()) return parMarque
            val declarees = lues.orEmpty()
            val traits = caracteristiques.split(',').map { it.trim().lowercase() }
            val tele = FONCTION_LEANBACK in declarees || FONCTION_TELEVISION in declarees || FONCTION_FIRE_TV in declarees ||
                "tv" in traits || (lues != null && FONCTION_TACTILE !in declarees)
            return when {
                tele -> if (parMarque.pourLeCatalogue) parMarque else TypeAppareil.TELEVISEUR
                "tablet" in traits -> TypeAppareil.TABLETTE
                else -> TypeAppareil.TELEPHONE
            }
        }

    /**
     * Le nom à montrer et à retenir : la marque vendue plutôt que le sous-traitant (« TPV »), puis le modèle
     * — sans la marque quand le modèle la porte déjà : la Philips relevée le 2026-10-04 déclare « Philips
     * Google TV TA1 », qui s'affichait « Philips Philips Google TV TA1 ».
     */
    val nomAffiche: String
        get() {
            val nomMarque = fabricant?.nom ?: marque
            val sansDoublon = nomMarque.isNotBlank() && modele.trim().startsWith("$nomMarque ", ignoreCase = true)
            return if (sansDoublon) modele.trim() else "$nomMarque $modele".trim()
        }

    /** Le même nom, prêt à entrer dans un nom de fichier : « Philips-55PUS8807-12 ». */
    val nomPourFichier: String
        get() = nomAffiche
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "televiseur" }

    companion object {
        val VIDE = InfosAppareil()

        const val FONCTION_LEANBACK = "android.software.leanback"
        const val FONCTION_TELEVISION = "android.hardware.type.television"
        const val FONCTION_FIRE_TV = "amazon.hardware.fire_tv"
        const val FONCTION_TACTILE = "android.hardware.touchscreen"

        /** Ce que la photographie demande à `pm list features` : le reste ne dit rien du genre d'appareil. */
        val FONCTIONS_LUES = listOf(FONCTION_LEANBACK, FONCTION_TELEVISION, FONCTION_FIRE_TV, FONCTION_TACTILE)
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

    /** Ce qu'occupe chaque paquet, ses processus réunis : « com.android.vending » et « …:background ». */
    val kilooctetsParPaquet: Map<String, Long>
        get() = processus.groupBy { it.paquet }.mapValues { (_, siens) -> siens.sumOf { it.kilooctets } }
}

data class LauncherInstalle(
    val paquet: String,
    val nom: String,
    val composant: String,
)

/** Un écran d'accueil d'usine, et s'il est encore actif. [composant] reste vide quand Android l'a tu. */
data class AccueilUsine(
    val paquet: String,
    val composant: String,
    val actif: Boolean,
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
