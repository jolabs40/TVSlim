package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry

/** Where a package comes from: the Android platform, the device manufacturer, or someone else. */
enum class PackageOrigin {
    ANDROID,
    MAKER,
    OTHER,
    ;

    companion object {
        /** Display order: manufacturer first, where the packages worth looking at are. */
        val ORDER = listOf(MAKER, ANDROID, OTHER)

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
        fun of(packageName: String, manufacturer: Manufacturer?): PackageOrigin {
            val name = packageName.lowercase()
            return when {
                CHIPMAKER_PREFIXES.any { name.startsWith(it) } -> MAKER
                name == "android" || PREFIXES_ANDROID.any { name.startsWith(it) } -> ANDROID
                manufacturer != null && manufacturer.ownsPackage(name) -> MAKER
                BRAND_PREFIXES[manufacturer].orEmpty().any { name.startsWith(it) } -> MAKER
                else -> OTHER
            }
        }

        private val PREFIXES_ANDROID = listOf("android.", "com.android.", "com.google.")

        private val CHIPMAKER_PREFIXES = listOf(
            "com.mediatek.", "com.mstar.", "com.realtek.", "com.amlogic.", "com.droidlogic.",
            "com.hisilicon.", "com.rockchip.", "com.allwinner.", "com.broadcom.",
        )

        /** Publishers whose name does not carry the brand: subsidiaries, contractors, sister brands. */
        private val BRAND_PREFIXES = mapOf(
            Manufacturer.PHILIPS to listOf("org.droidtv."),
            Manufacturer.HISENSE to listOf("com.jamdeo."),
            Manufacturer.XIAOMI to listOf("com.mitv.", "com.miui.", "com.duokan."),
            // Android Thomson TVs are made by TCL.
            Manufacturer.THOMSON to listOf("com.tcl."),
        )
    }
}

/** Origin of a catalogue entry from its `brand`, or guessed from its name like an unknown package. */
val PackageEntry.origin: PackageOrigin
    get() = when (brand.trim().lowercase()) {
        "google", "aosp", "android" -> PackageOrigin.ANDROID
        "third party" -> PackageOrigin.OTHER
        "" -> PackageOrigin.of(packageName, null)
        else -> PackageOrigin.MAKER
    }

/** A package shipped with the device that the catalogue does not describe yet. */
data class UnknownPackage(
    val packageName: String,
    val state: PackageState,
    val origin: PackageOrigin,
) {
    /**
     * Publisher grouping (`org.droidtv`, `com.mediatek`). The framework, `android`, has no domain, so its
     * overlays (`android.auto_generated_rro_vendor__`) are grouped with it rather than one family each.
     */
    val family: String
        get() = packageName.split('.').let { segments ->
            if (segments.first() == "android") "android" else segments.take(2).joinToString(".")
        }
}

/**
 * Shipped packages the catalogue does not know (not an entry, blocklisted or a known launcher), sorted by
 * origin then name. Shown read-only: an unknown system package may run the tuner, the inputs or the remote.
 * Exported by [UnknownsReport].
 */
fun Catalog.unknownPackages(system: Map<String, PackageState>, manufacturer: Manufacturer?): List<UnknownPackage> {
    val known = buildSet {
        entries.mapTo(this) { it.packageName }
        protectedPackages.mapTo(this) { it.packageName }
        launchers.forEach { add(it.packageName); addAll(it.variants) }
        knownLaunchers.forEach { addAll(it.packages) }
    }
    return system
        .filterKeys { it !in known }
        .map { (packageName, state) -> UnknownPackage(packageName, state, PackageOrigin.of(packageName, manufacturer)) }
        .sortedWith(compareBy<UnknownPackage> { PackageOrigin.ORDER.indexOf(it.origin) }.thenBy { it.packageName })
}
