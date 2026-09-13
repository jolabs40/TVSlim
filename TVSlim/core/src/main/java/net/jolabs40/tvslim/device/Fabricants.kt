package net.jolabs40.tvslim.device

/** Téléviseur ou box : ce que l'on dit de l'appareil joint. */
enum class TypeAppareil { TELEVISEUR, BOX }

/**
 * Les fabricants reconnus, pour nommer l'appareil et montrer son logo.
 *
 * Sur un téléviseur sous licence, `ro.product.manufacturer` porte le vrai fabricant — le sous-traitant
 * — et `ro.product.brand` la marque vendue : Philips sort en `TPV`, Panasonic en `SCBC`, les box
 * Thomson en `SkyworthDigital`. La marque se lit donc d'abord, le fabricant ensuite, sans tenir compte
 * de la casse : Google répond `google`, certaines Xiaomi `xiaomi`.
 *
 * Valeurs relevées dans des dumps de firmware et des rapports de bugs publics (Kodi, Jellyfin,
 * media3) en septembre 2026. Sharp, Grundig et Toshiba n'ont pas pu être vérifiés en Europe : ils
 * sont reconnus à leur nom, que leur marque porte ailleurs.
 */
enum class Fabricant(
    val nom: String,
    val type: TypeAppareil,
    /** Ce que la marque ou le fabricant contient, en minuscules et sans ponctuation. */
    private val signes: List<String>,
    /** Faux quand l'appareil est nommé sans logo. */
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

    // Reconnus, mais sans logo : aucun logotype officiel utilisable n'existe pour Thomson.
    THOMSON("Thomson", TypeAppareil.TELEVISEUR, listOf("thomson"), aUnLogo = false),
    NOKIA("Nokia", TypeAppareil.TELEVISEUR, listOf("nokia"), aUnLogo = false),
    SKYWORTH("Skyworth", TypeAppareil.TELEVISEUR, listOf("skyworth"), aUnLogo = false),

    NVIDIA("NVIDIA", TypeAppareil.BOX, listOf("nvidia")),
    GOOGLE("Google", TypeAppareil.BOX, listOf("google")),
    AMAZON("Amazon", TypeAppareil.BOX, listOf("amazon")),
    FREEBOX("Freebox", TypeAppareil.BOX, listOf("freebox")),
    ;

    /**
     * Vrai quand un segment du nom de paquet porte la marque : « com.tcl.tv », « com.nvidia.ota ».
     * Sert à reconnaître, parmi les paquets que le catalogue ignore, ceux du constructeur.
     */
    fun signePaquet(paquet: String): Boolean =
        paquet.lowercase().split('.').any { segment -> signes.any { segment.startsWith(it) } }

    /** Téléviseur ou box : Xiaomi fait les deux, et le modèle le dit (« MIBOX4 », « Mi TV Stick »). */
    fun typePour(modele: String): TypeAppareil {
        val nomModele = normaliser(modele)
        return if (this == XIAOMI && MOTS_DE_BOX.any { it in nomModele }) TypeAppareil.BOX else type
    }

    companion object {
        private val MOTS_DE_BOX = listOf("box", "stick")

        /** La marque vendue d'abord, le fabricant ensuite. `null` pour une marque inconnue. */
        fun identifier(marqueCommerciale: String, fabricant: String): Fabricant? =
            reconnaitre(marqueCommerciale) ?: reconnaitre(fabricant)

        /**
         * Depuis le nom retenu d'un appareil déjà joint (« TCL Smart TV Pro ») : il commence par sa
         * marque, voir [InfosAppareil.nomAffiche].
         */
        fun depuisNom(nom: String): Fabricant? = reconnaitre(nom.trim().substringBefore(' '))

        private fun reconnaitre(texte: String): Fabricant? {
            val normalise = normaliser(texte)
            if (normalise.isEmpty()) return null
            return entries.firstOrNull { fabricant -> fabricant.signes.any { it in normalise } }
        }

        private fun normaliser(texte: String): String = texte.lowercase().filter { it.isLetterOrDigit() }
    }
}
