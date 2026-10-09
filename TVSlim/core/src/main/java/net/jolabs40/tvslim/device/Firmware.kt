package net.jolabs40.tvslim.device

/**
 * Identifies a firmware rather than a device: two TVs of the same model sold in the same region report the
 * same values. Nothing personal (no user language, no serial number).
 */
data class Firmware(
    /** `ro.build.fingerprint`: brand, product, device, Android version and build; enough to spot duplicates. */
    val empreinte: String = "",
    /** `ro.product.name`: the product, which often includes the region (`G08_4K_GB` on the TCL). */
    val produit: String = "",
    /** `ro.product.locale`: the factory language, not the one the user picked. */
    val langueUsine: String = "",
) {
    val renseigne: Boolean get() = empreinte.isNotEmpty() || produit.isNotEmpty() || langueUsine.isNotEmpty()
}

/**
 * Reads the [Firmware] in one command, for the unknown-packages inventory: preinstalled packages vary by region
 * and by firmware version.
 */
object LectureFirmware {

    const val MARQUEUR_EMPREINTE = "@@TVSLIM_EMPREINTE"
    const val MARQUEUR_PRODUIT = "@@TVSLIM_PRODUIT"
    const val MARQUEUR_LANGUE = "@@TVSLIM_LANGUE"

    /** One section per property, so an empty value leaves an empty section without shifting the others. */
    val COMMANDE: String = listOf(
        "echo $MARQUEUR_EMPREINTE",
        "getprop ro.build.fingerprint",
        "echo $MARQUEUR_PRODUIT",
        "getprop ro.product.name",
        "echo $MARQUEUR_LANGUE",
        "getprop ro.product.locale",
    ).joinToString("; ")

    fun interpreter(sortie: String): Firmware {
        val sections = LecteurDistant.decouper(sortie)
        fun valeur(marqueur: String) = sections[marqueur].orEmpty().firstOrNull().orEmpty()
        return Firmware(
            empreinte = valeur(MARQUEUR_EMPREINTE),
            produit = valeur(MARQUEUR_PRODUIT),
            langueUsine = valeur(MARQUEUR_LANGUE),
        )
    }
}
