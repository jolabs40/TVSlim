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
data class BootSnapshot(
    val fingerprint: String,
    val disabled: Set<String>,
    val home: String,
)

/**
 * What a system update undid between two boots.
 *
 * @param rallumes packages disabled before the update and enabled since.
 * @param accueilPerdu launcher that held the home screen before the update, when the factory home took over.
 */
data class BootDrift(
    val reenabled: List<String>,
    val lostHome: String?,
) {
    val empty: Boolean get() = reenabled.isEmpty() && lostHome == null

    /** What is still to fix once the TV is read again: a package disabled again since no longer counts. */
    fun remaining(active: Set<String>, home: String, factoryHomes: Set<String>): BootDrift = BootDrift(
        reenabled = reenabled.filter { it in active },
        lostHome = lostHome?.takeIf { hasFallenBack(home, factoryHomes) },
    )
}

/**
 * Compares this boot with the previous one. Returns null without a system update in between or without a
 * previous photo: the first boot only sets the baseline.
 *
 * @param actifs catalogue packages enabled at this boot.
 * @param accueilsUsine factory homes known to the catalogue (Google TV, its setup wizard...).
 */
fun BootSnapshot.driftSince(
    before: BootSnapshot?,
    active: Set<String>,
    factoryHomes: Set<String>,
): BootDrift? {
    if (before == null || before.fingerprint.isBlank() || before.fingerprint == fingerprint) return null
    val drift = BootDrift(
        reenabled = before.disabled.filter { it in active }.sorted(),
        // A third-party home replaced by the factory one, which is what the user sees on power-up.
        lostHome = before.home.takeIf { thirdParty ->
            thirdParty.isNotBlank() && !hasFallenBack(thirdParty, factoryHomes) && hasFallenBack(home, factoryHomes)
        },
    )
    return drift.takeUnless { it.empty }
}

/** The factory home, or Android's chooser, shown when two homes compete. */
private fun hasFallenBack(home: String, factoryHomes: Set<String>): Boolean =
    home == "android" || home in factoryHomes
