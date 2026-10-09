package net.jolabs40.tvslim.device

/**
 * Identifies a firmware rather than a device: two TVs of the same model sold in the same region report the
 * same values. Nothing personal (no user language, no serial number).
 */
data class Firmware(
    /** `ro.build.fingerprint`: brand, product, device, Android version and build; enough to spot duplicates. */
    val fingerprint: String = "",
    /** `ro.product.name`: the product, which often includes the region (`G08_4K_GB` on the TCL). */
    val product: String = "",
    /** `ro.product.locale`: the factory language, not the one the user picked. */
    val factoryLanguage: String = "",
) {
    val populated: Boolean get() = fingerprint.isNotEmpty() || product.isNotEmpty() || factoryLanguage.isNotEmpty()
}

/**
 * Reads the [Firmware] in one command, for the unknown-packages inventory: preinstalled packages vary by region
 * and by firmware version.
 */
object FirmwareReading {

    const val FINGERPRINT_MARKER = "@@TVSLIM_FINGERPRINT"
    const val PRODUCT_MARKER = "@@TVSLIM_PRODUCT"
    const val LANGUAGE_MARKER = "@@TVSLIM_LANGUAGE"

    /** One section per property, so an empty value leaves an empty section without shifting the others. */
    val COMMAND: String = listOf(
        "echo $FINGERPRINT_MARKER",
        "getprop ro.build.fingerprint",
        "echo $PRODUCT_MARKER",
        "getprop ro.product.name",
        "echo $LANGUAGE_MARKER",
        "getprop ro.product.locale",
    ).joinToString("; ")

    fun parse(output: String): Firmware {
        val sections = RemoteReader.splitSections(output)
        fun rawValue(marker: String) = sections[marker].orEmpty().firstOrNull().orEmpty()
        return Firmware(
            fingerprint = rawValue(FINGERPRINT_MARKER),
            product = rawValue(PRODUCT_MARKER),
            factoryLanguage = rawValue(LANGUAGE_MARKER),
        )
    }
}
