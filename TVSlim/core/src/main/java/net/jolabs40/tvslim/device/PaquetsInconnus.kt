package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.Catalogue
import net.jolabs40.tvslim.catalog.EntreePaquet

/** Where a package comes from: the Android platform, the device manufacturer, or someone else. */
enum class OriginePaquet {
    ANDROID,
    CONSTRUCTEUR,
    AUTRE,
    ;

    companion object {
        /** Display order: manufacturer first, where the packages worth looking at are. */
        val ORDRE = listOf(CONSTRUCTEUR, ANDROID, AUTRE)

        /**
         * Guesses the origin of a package the catalogue does not describe, from its name:
         *
         *  - chip vendors (MediaTek, Realtek, Amlogic...): manufacturer, whatever the brand;
         *  - `android.`, `com.android.`, `com.google.`: Android;
         *  - the device brand in a name segment (`com.tcl.tv`) or one of its known prefixes
         *    (`org.droidtv` for Philips): manufacturer;
         *  - anything else (preinstalled partners, operators): other.
         *
         * Only a guess, used to group and display, never to decide anything.
         */
        fun de(paquet: String, fabricant: Fabricant?): OriginePaquet {
            val nom = paquet.lowercase()
            return when {
                PREFIXES_FONDEURS.any { nom.startsWith(it) } -> CONSTRUCTEUR
                nom == "android" || PREFIXES_ANDROID.any { nom.startsWith(it) } -> ANDROID
                fabricant != null && fabricant.signePaquet(nom) -> CONSTRUCTEUR
                PREFIXES_MARQUES[fabricant].orEmpty().any { nom.startsWith(it) } -> CONSTRUCTEUR
                else -> AUTRE
            }
        }

        private val PREFIXES_ANDROID = listOf("android.", "com.android.", "com.google.")

        private val PREFIXES_FONDEURS = listOf(
            "com.mediatek.", "com.mstar.", "com.realtek.", "com.amlogic.", "com.droidlogic.",
            "com.hisilicon.", "com.rockchip.", "com.allwinner.", "com.broadcom.",
        )

        /** Publishers whose name does not carry the brand: subsidiaries, contractors, sister brands. */
        private val PREFIXES_MARQUES = mapOf(
            Fabricant.PHILIPS to listOf("org.droidtv."),
            Fabricant.HISENSE to listOf("com.jamdeo."),
            Fabricant.XIAOMI to listOf("com.mitv.", "com.miui.", "com.duokan."),
            // Android Thomson TVs are made by TCL.
            Fabricant.THOMSON to listOf("com.tcl."),
        )
    }
}

/** Origin of a catalogue entry from its `marque`, or guessed from its name like an unknown package. */
val EntreePaquet.origine: OriginePaquet
    get() = when (marque.trim().lowercase()) {
        "google", "aosp", "android" -> OriginePaquet.ANDROID
        "third party" -> OriginePaquet.AUTRE
        "" -> OriginePaquet.de(paquet, null)
        else -> OriginePaquet.CONSTRUCTEUR
    }

/** A package shipped with the device that the catalogue does not describe yet. */
data class PaquetInconnu(
    val paquet: String,
    val etat: EtatPaquet,
    val origine: OriginePaquet,
) {
    /**
     * Publisher grouping (`org.droidtv`, `com.mediatek`). The framework, `android`, has no domain, so its
     * overlays (`android.auto_generated_rro_vendor__`) are grouped with it rather than one family each.
     */
    val famille: String
        get() = paquet.split('.').let { segments ->
            if (segments.first() == "android") "android" else segments.take(2).joinToString(".")
        }
}

/**
 * Shipped packages the catalogue does not know (not an entry, blocklisted or a known launcher), sorted by
 * origin then name. Shown read-only: an unknown system package may run the tuner, the inputs or the remote.
 * Exported by [RapportInconnus].
 */
fun Catalogue.paquetsInconnus(systeme: Map<String, EtatPaquet>, fabricant: Fabricant?): List<PaquetInconnu> {
    val connus = buildSet {
        entrees.mapTo(this) { it.paquet }
        proteges.mapTo(this) { it.paquet }
        launchers.forEach { add(it.paquet); addAll(it.variantes) }
        launchersConnus.forEach { addAll(it.paquets) }
    }
    return systeme
        .filterKeys { it !in connus }
        .map { (paquet, etat) -> PaquetInconnu(paquet, etat, OriginePaquet.de(paquet, fabricant)) }
        .sortedWith(compareBy<PaquetInconnu> { OriginePaquet.ORDRE.indexOf(it.origine) }.thenBy { it.paquet })
}
