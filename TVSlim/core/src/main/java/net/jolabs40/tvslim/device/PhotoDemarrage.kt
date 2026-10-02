package net.jolabs40.tvslim.device

/**
 * Ce que le gardien de démarrage retient du téléviseur à chaque allumage : de quoi reconnaître, au
 * suivant, ce qu'une mise à jour système a défait.
 *
 * L'application du téléviseur n'a pas le journal — il vit sur le téléphone ou le PC. Elle compare donc
 * deux allumages, et c'est l'**empreinte du firmware** qui dit qu'une mise à jour est passée entre eux.
 * Sans elle, un paquet rallumé depuis le téléphone, puis le téléviseur redémarré, passerait pour une
 * dérive.
 *
 * @param empreinte `Build.FINGERPRINT` : elle change à chaque mise à jour système, et seulement alors.
 * @param desactives les paquets du catalogue désactivés à cet allumage.
 * @param accueil le paquet de l'écran d'accueil en place.
 */
data class PhotoDemarrage(
    val empreinte: String,
    val desactives: Set<String>,
    val accueil: String,
)

/**
 * Ce qu'une mise à jour système a défait entre deux allumages.
 *
 * @param rallumes les paquets désactivés avant la mise à jour, actifs depuis.
 * @param accueilPerdu le launcher qui tenait l'accueil avant elle, quand celui d'usine l'a repris.
 */
data class DeriveDemarrage(
    val rallumes: List<String>,
    val accueilPerdu: String?,
) {
    val vide: Boolean get() = rallumes.isEmpty() && accueilPerdu == null

    /** Ce qui reste à reprendre, le téléviseur relu : un paquet recoupé depuis ne compte plus. */
    fun restant(actifs: Set<String>, accueil: String, accueilsUsine: Set<String>): DeriveDemarrage = DeriveDemarrage(
        rallumes = rallumes.filter { it in actifs },
        accueilPerdu = accueilPerdu?.takeIf { estRetombe(accueil, accueilsUsine) },
    )
}

/**
 * Compare cet allumage au précédent. Rien sans mise à jour système entre les deux, ni sans photo
 * précédente : le premier démarrage ne fait que poser la référence.
 *
 * @param actifs les paquets du catalogue actifs à cet allumage.
 * @param accueilsUsine les accueils d'usine que le catalogue connaît (Google TV, son assistant…).
 */
fun PhotoDemarrage.deriveDepuis(
    avant: PhotoDemarrage?,
    actifs: Set<String>,
    accueilsUsine: Set<String>,
): DeriveDemarrage? {
    if (avant == null || avant.empreinte.isBlank() || avant.empreinte == empreinte) return null
    val derive = DeriveDemarrage(
        rallumes = avant.desactives.filter { it in actifs }.sorted(),
        // Un accueil tiers qui cède la place à celui d'usine : c'est ce qu'on voit en allumant le téléviseur.
        accueilPerdu = avant.accueil.takeIf { tiers ->
            tiers.isNotBlank() && !estRetombe(tiers, accueilsUsine) && estRetombe(accueil, accueilsUsine)
        },
    )
    return derive.takeUnless { it.vide }
}

/** L'accueil d'usine, ou le sélecteur d'Android que montre un téléviseur où deux accueils se disputent. */
private fun estRetombe(accueil: String, accueilsUsine: Set<String>): Boolean =
    accueil == "android" || accueil in accueilsUsine
