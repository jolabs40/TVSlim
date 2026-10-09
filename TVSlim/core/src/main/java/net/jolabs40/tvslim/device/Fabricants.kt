package net.jolabs40.tvslim.device

enum class TypeAppareil {
    TELEVISEUR,
    BOX,

    /** Phones and tablets: nothing in the catalogue is written for them. */
    TELEPHONE,
    TABLETTE,
    ;

    /** What the catalogue is written for: a TV, or a box plugged into one. Profiles only apply there. */
    val pourLeCatalogue: Boolean get() = this == TELEVISEUR || this == BOX
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
enum class Fabricant(
    val nom: String,
    val type: TypeAppareil,
    /** Substrings of the brand or manufacturer, lowercase, without punctuation. */
    private val signes: List<String>,
    val aUnLogo: Boolean = true,
) {
    TCL("TCL", TypeAppareil.TELEVISEUR, listOf("tcl")),
    HISENSE("Hisense", TypeAppareil.TELEVISEUR, listOf("hisense")),
    PHILIPS("Philips", TypeAppareil.TELEVISEUR, listOf("philips", "tpv")),
    SONY("Sony", TypeAppareil.TELEVISEUR, listOf("sony")),
    XIAOMI("Xiaomi", TypeAppareil.TELEVISEUR, listOf("xiaomi")),
    SHARP("Sharp", TypeAppareil.TELEVISEUR, listOf("sharp")),
    GRUNDIG("Grundig", TypeAppareil.TELEVISEUR, listOf("grundig")),
    TOSHIBA("Toshiba", TypeAppareil.TELEVISEUR, listOf("toshiba")),
    HAIER("Haier", TypeAppareil.TELEVISEUR, listOf("haier")),
    PANASONIC("Panasonic", TypeAppareil.TELEVISEUR, listOf("panasonic")),

    // Recognized but shown without a logo: there is no usable official Thomson logo.
    THOMSON("Thomson", TypeAppareil.TELEVISEUR, listOf("thomson"), aUnLogo = false),
    NOKIA("Nokia", TypeAppareil.TELEVISEUR, listOf("nokia"), aUnLogo = false),
    SKYWORTH("Skyworth", TypeAppareil.TELEVISEUR, listOf("skyworth"), aUnLogo = false),

    NVIDIA("NVIDIA", TypeAppareil.BOX, listOf("nvidia")),
    GOOGLE("Google", TypeAppareil.BOX, listOf("google")),
    AMAZON("Amazon", TypeAppareil.BOX, listOf("amazon")),
    FREEBOX("Freebox", TypeAppareil.BOX, listOf("freebox")),
    ;

    /**
     * True when a segment of the package name carries the brand (`com.tcl.tv`, `com.nvidia.ota`). Used to
     * spot the manufacturer's packages among those the catalogue does not know.
     */
    fun signePaquet(paquet: String): Boolean =
        paquet.lowercase().split('.').any { segment -> signes.any { segment.startsWith(it) } }

    /** TV or box: Xiaomi makes both, and the model says which ("MIBOX4", "Mi TV Stick"). */
    fun typePour(modele: String): TypeAppareil {
        val nomModele = normaliser(modele)
        return if (this == XIAOMI && MOTS_DE_BOX.any { it in nomModele }) TypeAppareil.BOX else type
    }

    companion object {
        private val MOTS_DE_BOX = listOf("box", "stick")

        /** Brand sold first, then manufacturer. `null` for an unknown brand. */
        fun identifier(marqueCommerciale: String, fabricant: String): Fabricant? =
            reconnaitre(marqueCommerciale) ?: reconnaitre(fabricant)

        /** From the stored name of a known device ("TCL Smart TV Pro"); see [InfosAppareil.nomAffiche]. */
        fun depuisNom(nom: String): Fabricant? = reconnaitre(nom.trim().substringBefore(' '))

        private fun reconnaitre(texte: String): Fabricant? {
            val normalise = normaliser(texte)
            if (normalise.isEmpty()) return null
            return entries.firstOrNull { fabricant -> fabricant.signes.any { it in normalise } }
        }

        private fun normaliser(texte: String): String = texte.lowercase().filter { it.isLetterOrDigit() }
    }
}
