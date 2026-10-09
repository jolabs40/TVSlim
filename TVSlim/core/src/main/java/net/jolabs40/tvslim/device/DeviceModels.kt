package net.jolabs40.tvslim.device

enum class PackageState { ABSENT, ACTIVE, DISABLED }

/** Snapshot of a TV, shown before and after a change. */
data class DeviceInfo(
    val brand: String = "",
    /** Brand sold (`ro.product.brand`) when it differs from the manufacturer, who may build for several brands. */
    val retailBrand: String = "",
    val model: String = "",
    val androidVersion: String = "",
    val build: String = "",
    val totalMemoryMb: Long = 0,
    val freeMemoryMb: Long = 0,
    val installedPackages: Int = 0,
    val disabledPackages: Int = 0,
    val currentHome: String = "",
    /** Full component of the current home (`package/.Activity`), which is what could be restored. */
    val homeComponent: String = "",
    val thirdPartyLaunchers: List<InstalledLauncher> = emptyList(),
    /** Home screens shipped with the device (Google TV, Android TV home, the maker's), disabled ones included. */
    val factoryHomes: List<FactoryHome> = emptyList(),
    /** `ro.build.characteristics`: `tv` on the TCL, `nosdcard` on a Pixel, `tablet` on a tablet. */
    val characteristics: String = "",
    /**
     * Declared features that tell the device kind ([QUERIED_FEATURES]), without the `feature:` prefix. `null`
     * until read; empty means the device declares none of them, not even a touchscreen.
     */
    val features: Set<String>? = null,
) {
    /** Recognized manufacturer, brand sold first; see [Manufacturer]. */
    val manufacturer: Manufacturer? get() = Manufacturer.identify(retailBrand, brand)

    /**
     * Declared features win over the brand, since Google makes both boxes (Chromecast) and phones (Pixel).
     * A TV has `leanback` (required on Android TV), `type.television`, the Fire TV feature, the `tv`
     * characteristic, or no touchscreen; the brand then picks TV or box. Anything else is a phone, or a
     * tablet if declared. Before the device has answered, the brand decides alone and unknown means TV.
     */
    val deviceType: DeviceType
        get() {
            val byBrand = manufacturer?.typeFor(model) ?: DeviceType.TV
            val fetched = features
            if (fetched == null && characteristics.isBlank()) return byBrand
            val declared = fetched.orEmpty()
            val traits = characteristics.split(',').map { it.trim().lowercase() }
            val looksLikeTv = FEATURE_LEANBACK in declared || FEATURE_TELEVISION in declared || FEATURE_FIRE_TV in declared ||
                "tv" in traits || (fetched != null && FEATURE_TOUCHSCREEN !in declared)
            return when {
                looksLikeTv -> if (byBrand.forCatalog) byBrand else DeviceType.TV
                "tablet" in traits -> DeviceType.TABLET
                else -> DeviceType.PHONE
            }
        }

    /**
     * Name to show and store: the brand sold rather than the contractor (`TPV`), then the model, without
     * the brand when the model already starts with it (a Philips reports "Philips Google TV TA1").
     */
    val displayName: String
        get() {
            val brandName = manufacturer?.displayName ?: brand
            val modelHasBrand = brandName.isNotBlank() && model.trim().startsWith("$brandName ", ignoreCase = true)
            return if (modelHasBrand) model.trim() else "$brandName $model".trim()
        }

    /** The same name, safe for a file name: `Philips-55PUS8807-12`. */
    val fileSafeName: String
        get() = displayName
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "televiseur" }

    companion object {
        val EMPTY = DeviceInfo()

        const val FEATURE_LEANBACK = "android.software.leanback"
        const val FEATURE_TELEVISION = "android.hardware.type.television"
        const val FEATURE_FIRE_TV = "amazon.hardware.fire_tv"
        const val FEATURE_TOUCHSCREEN = "android.hardware.touchscreen"

        /** Features the snapshot asks `pm list features` for; the others say nothing about the device kind. */
        val QUERIED_FEATURES = listOf(FEATURE_LEANBACK, FEATURE_TELEVISION, FEATURE_FIRE_TV, FEATURE_TOUCHSCREEN)
    }
}

/** A live process and the memory it actually uses (PSS). */
data class MemoryProcess(
    val name: String,
    val pid: Int,
    val kilobytes: Long,
) {
    val megabytes: Long get() = kilobytes / 1024

    /** Package behind the process: `com.android.vending:background` belongs to `com.android.vending`. */
    val packageName: String get() = name.substringBefore(':')

    /**
     * System processes have no package name (`surfaceflinger`, `system`, `vendor.nvidia...`). They are
     * never offered for stopping: at best they restart at once, at worst the device misbehaves.
     */
    val isApp: Boolean
        get() = packageName.count { it == '.' } >= 2 && !packageName.startsWith("vendor.")
}

/** Memory breakdown as reported by `dumpsys meminfo`. */
data class MemoryBreakdown(
    val totalKb: Long = 0,
    val freeKb: Long = 0,
    val usedKb: Long = 0,
    val cacheKb: Long = 0,
    val zramKb: Long = 0,
    val processes: List<MemoryProcess> = emptyList(),
) {
    val populated: Boolean get() = totalKb > 0

    /** Memory per package, its processes combined (`com.android.vending` and `...:background`). */
    val kilobytesPerPackage: Map<String, Long>
        get() = processes.groupBy { it.packageName }.mapValues { (_, packageProcesses) -> packageProcesses.sumOf { it.kilobytes } }
}

data class InstalledLauncher(
    val packageName: String,
    val name: String,
    val component: String,
)

/** A factory home screen and whether it is still enabled. [component] is empty when Android did not report it. */
data class FactoryHome(
    val packageName: String,
    val component: String,
    val active: Boolean,
)

/**
 * Permissions an app requests and those it was granted. A permission missing from [requested] cannot be
 * granted; `pm grant` would refuse it too, but with a Java exception instead of a readable message.
 */
data class PackagePermissions(
    val packageFound: Boolean = false,
    val requested: Set<String> = emptySet(),
    val granted: Set<String> = emptySet(),
) {
    fun isGranted(permission: String): Boolean = permission in granted

    fun isDeclared(permission: String): Boolean = permission in requested
}
