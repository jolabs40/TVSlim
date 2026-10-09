package net.jolabs40.tvslim.device

/**
 * What the boot guard records at each boot, so the next boot can tell what a system update undid.
 *
 * The TV app has no journal (it lives on the phone or PC), so it compares two boots and relies on the
 * firmware fingerprint to know an update happened in between. Otherwise a package re-enabled from the phone
 * followed by a reboot would look like drift.
 *
 * @param empreinte `Build.FINGERPRINT`, which changes on every system update and only then.
 * @param desactives catalogue packages disabled at this boot.
 * @param accueil package of the current home screen.
 */
data class PhotoDemarrage(
    val empreinte: String,
    val desactives: Set<String>,
    val accueil: String,
)

/**
 * What a system update undid between two boots.
 *
 * @param rallumes packages disabled before the update and enabled since.
 * @param accueilPerdu launcher that held the home screen before the update, when the factory home took over.
 */
data class DeriveDemarrage(
    val rallumes: List<String>,
    val accueilPerdu: String?,
) {
    val vide: Boolean get() = rallumes.isEmpty() && accueilPerdu == null

    /** What is still to fix once the TV is read again: a package disabled again since no longer counts. */
    fun restant(actifs: Set<String>, accueil: String, accueilsUsine: Set<String>): DeriveDemarrage = DeriveDemarrage(
        rallumes = rallumes.filter { it in actifs },
        accueilPerdu = accueilPerdu?.takeIf { estRetombe(accueil, accueilsUsine) },
    )
}

/**
 * Compares this boot with the previous one. Returns null without a system update in between or without a
 * previous photo: the first boot only sets the baseline.
 *
 * @param actifs catalogue packages enabled at this boot.
 * @param accueilsUsine factory homes known to the catalogue (Google TV, its setup wizard...).
 */
fun PhotoDemarrage.deriveDepuis(
    avant: PhotoDemarrage?,
    actifs: Set<String>,
    accueilsUsine: Set<String>,
): DeriveDemarrage? {
    if (avant == null || avant.empreinte.isBlank() || avant.empreinte == empreinte) return null
    val derive = DeriveDemarrage(
        rallumes = avant.desactives.filter { it in actifs }.sorted(),
        // A third-party home replaced by the factory one, which is what the user sees on power-up.
        accueilPerdu = avant.accueil.takeIf { tiers ->
            tiers.isNotBlank() && !estRetombe(tiers, accueilsUsine) && estRetombe(accueil, accueilsUsine)
        },
    )
    return derive.takeUnless { it.vide }
}

/** The factory home, or Android's chooser, shown when two homes compete. */
private fun estRetombe(accueil: String, accueilsUsine: Set<String>): Boolean =
    accueil == "android" || accueil in accueilsUsine
