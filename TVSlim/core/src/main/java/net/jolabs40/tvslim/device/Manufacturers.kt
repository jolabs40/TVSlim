package net.jolabs40.tvslim.device

enum class DeviceType {
    TV,
    BOX,

    /** Phones and tablets: nothing in the catalogue is written for them. */
    PHONE,
    TABLET,
    ;

    /** What the catalogue is written for: a TV, or a box plugged into one. Profiles only apply there. */
    val forCatalog: Boolean get() = this == TV || this == BOX
}

/**
 * Recognized manufacturers, used to name the device and show its logo.
 *
 * On licensed TVs `ro.product.manufacturer` holds the actual maker (the contractor) and `ro.product.brand`
 * the brand sold: Philips reports `TPV`, Panasonic `SCBC`, Thomson boxes `SkyworthDigital`. So the brand is
 * checked first, then the manufacturer, ignoring case (Google reports `google`, some Xiaomi `xiaomi`).
 *
 * Values taken from firmware dumps and public bug reports (Kodi, Jellyfin, media3). Sharp, Grundig and
 * Toshiba could not be checked on European devices and are matched on their name only.
 */
enum class Manufacturer(
    val displayName: String,
    val type: DeviceType,
    /** Substrings of the brand or manufacturer, lowercase, without punctuation. */
    private val marks: List<String>,
    val hasLogo: Boolean = true,
) {
    TCL("TCL", DeviceType.TV, listOf("tcl")),
    HISENSE("Hisense", DeviceType.TV, listOf("hisense")),
    PHILIPS("Philips", DeviceType.TV, listOf("philips", "tpv")),
    SONY("Sony", DeviceType.TV, listOf("sony")),
    XIAOMI("Xiaomi", DeviceType.TV, listOf("xiaomi")),
    SHARP("Sharp", DeviceType.TV, listOf("sharp")),
    GRUNDIG("Grundig", DeviceType.TV, listOf("grundig")),
    TOSHIBA("Toshiba", DeviceType.TV, listOf("toshiba")),
    HAIER("Haier", DeviceType.TV, listOf("haier")),
    PANASONIC("Panasonic", DeviceType.TV, listOf("panasonic")),

    // Recognized but shown without a logo: there is no usable official Thomson logo.
    THOMSON("Thomson", DeviceType.TV, listOf("thomson"), hasLogo = false),
    NOKIA("Nokia", DeviceType.TV, listOf("nokia"), hasLogo = false),
    SKYWORTH("Skyworth", DeviceType.TV, listOf("skyworth"), hasLogo = false),

    NVIDIA("NVIDIA", DeviceType.BOX, listOf("nvidia")),
    GOOGLE("Google", DeviceType.BOX, listOf("google")),
    AMAZON("Amazon", DeviceType.BOX, listOf("amazon")),
    FREEBOX("Freebox", DeviceType.BOX, listOf("freebox")),
    ;

    /**
     * True when a segment of the package name carries the brand (`com.tcl.tv`, `com.nvidia.ota`). Used to
     * spot the manufacturer's packages among those the catalogue does not know.
     */
    fun ownsPackage(packageName: String): Boolean =
        packageName.lowercase().split('.').any { segment -> marks.any { segment.startsWith(it) } }

    /** TV or box: Xiaomi makes both, and the model says which ("MIBOX4", "Mi TV Stick"). */
    fun typeFor(model: String): DeviceType {
        val modelName = normalize(model)
        return if (this == XIAOMI && BOX_WORDS.any { it in modelName }) DeviceType.BOX else type
    }

    companion object {
        private val BOX_WORDS = listOf("box", "stick")

        /** Brand sold first, then manufacturer. `null` for an unknown brand. */
        fun identify(retailBrand: String, manufacturer: String): Manufacturer? =
            recognize(retailBrand) ?: recognize(manufacturer)

        /** From the stored name of a known device ("TCL Smart TV Pro"); see [DeviceInfo.displayName]. */
        fun fromName(name: String): Manufacturer? = recognize(name.trim().substringBefore(' '))

        private fun recognize(text: String): Manufacturer? {
            val normalized = normalize(text)
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { manufacturer -> manufacturer.marks.any { it in normalized } }
        }

        private fun normalize(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }
    }
}
