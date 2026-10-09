package net.jolabs40.tvslim.device

enum class EtatPaquet { ABSENT, ACTIF, DESACTIVE }

/** Snapshot of a TV, shown before and after a change. */
data class InfosAppareil(
    val marque: String = "",
    /** Brand sold (`ro.product.brand`) when it differs from the manufacturer, who may build for several brands. */
    val marqueCommerciale: String = "",
    val modele: String = "",
    val versionAndroid: String = "",
    val build: String = "",
    val memoireTotaleMo: Long = 0,
    val memoireLibreMo: Long = 0,
    val paquetsInstalles: Int = 0,
    val paquetsDesactives: Int = 0,
    val accueilActuel: String = "",
    /** Full component of the current home (`package/.Activity`), which is what could be restored. */
    val composantAccueil: String = "",
    val launchersTiers: List<LauncherInstalle> = emptyList(),
    /** Home screens shipped with the device (Google TV, Android TV home, the maker's), disabled ones included. */
    val accueilsUsine: List<AccueilUsine> = emptyList(),
    /** `ro.build.characteristics`: `tv` on the TCL, `nosdcard` on a Pixel, `tablet` on a tablet. */
    val caracteristiques: String = "",
    /**
     * Declared features that tell the device kind ([FONCTIONS_LUES]), without the `feature:` prefix. `null`
     * until read; empty means the device declares none of them, not even a touchscreen.
     */
    val fonctions: Set<String>? = null,
) {
    /** Recognized manufacturer, brand sold first; see [Fabricant]. */
    val fabricant: Fabricant? get() = Fabricant.identifier(marqueCommerciale, marque)

    /**
     * Declared features win over the brand, since Google makes both boxes (Chromecast) and phones (Pixel).
     * A TV has `leanback` (required on Android TV), `type.television`, the Fire TV feature, the `tv`
     * characteristic, or no touchscreen; the brand then picks TV or box. Anything else is a phone, or a
     * tablet if declared. Before the device has answered, the brand decides alone and unknown means TV.
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
     * Name to show and store: the brand sold rather than the contractor (`TPV`), then the model, without
     * the brand when the model already starts with it (a Philips reports "Philips Google TV TA1").
     */
    val nomAffiche: String
        get() {
            val nomMarque = fabricant?.nom ?: marque
            val sansDoublon = nomMarque.isNotBlank() && modele.trim().startsWith("$nomMarque ", ignoreCase = true)
            return if (sansDoublon) modele.trim() else "$nomMarque $modele".trim()
        }

    /** The same name, safe for a file name: `Philips-55PUS8807-12`. */
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

        /** Features the snapshot asks `pm list features` for; the others say nothing about the device kind. */
        val FONCTIONS_LUES = listOf(FONCTION_LEANBACK, FONCTION_TELEVISION, FONCTION_FIRE_TV, FONCTION_TACTILE)
    }
}

/** A live process and the memory it actually uses (PSS). */
data class ProcessusMemoire(
    val nom: String,
    val pid: Int,
    val kilooctets: Long,
) {
    val megaoctets: Long get() = kilooctets / 1024

    /** Package behind the process: `com.android.vending:background` belongs to `com.android.vending`. */
    val paquet: String get() = nom.substringBefore(':')

    /**
     * System processes have no package name (`surfaceflinger`, `system`, `vendor.nvidia...`). They are
     * never offered for stopping: at best they restart at once, at worst the device misbehaves.
     */
    val estUneApplication: Boolean
        get() = paquet.count { it == '.' } >= 2 && !paquet.startsWith("vendor.")
}

/** Memory breakdown as reported by `dumpsys meminfo`. */
data class RepartitionMemoire(
    val totalKo: Long = 0,
    val libreKo: Long = 0,
    val utiliseeKo: Long = 0,
    val cacheKo: Long = 0,
    val zramKo: Long = 0,
    val processus: List<ProcessusMemoire> = emptyList(),
) {
    val renseignee: Boolean get() = totalKo > 0

    /** Memory per package, its processes combined (`com.android.vending` and `...:background`). */
    val kilooctetsParPaquet: Map<String, Long>
        get() = processus.groupBy { it.paquet }.mapValues { (_, siens) -> siens.sumOf { it.kilooctets } }
}

data class LauncherInstalle(
    val paquet: String,
    val nom: String,
    val composant: String,
)

/** A factory home screen and whether it is still enabled. [composant] is empty when Android did not report it. */
data class AccueilUsine(
    val paquet: String,
    val composant: String,
    val actif: Boolean,
)

/**
 * Permissions an app requests and those it was granted. A permission missing from [demandees] cannot be
 * granted; `pm grant` would refuse it too, but with a Java exception instead of a readable message.
 */
data class PermissionsPaquet(
    val paquetTrouve: Boolean = false,
    val demandees: Set<String> = emptySet(),
    val accordees: Set<String> = emptySet(),
) {
    fun estAccordee(permission: String): Boolean = permission in accordees

    fun estDeclaree(permission: String): Boolean = permission in demandees
}
