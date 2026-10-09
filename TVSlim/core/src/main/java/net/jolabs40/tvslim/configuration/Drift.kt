package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.lastHome
import net.jolabs40.tvslim.journal.disabledPackages

/**
 * Drift: what TV Slim disabled on this TV that came back without it, usually after a system update
 * re-enabled packages and restored the stock home screen.
 *
 * The desired state comes from the journal (packages it leaves disabled, last home screen set).
 * Compared with the TV as just read, it yields an ordinary [ReinjectionPlan], confirmed and applied
 * like a backup. A package re-enabled by hand from the TV settings is listed too; the user can decline.
 *
 * Only a fallback to the stock home screen counts as home drift: another launcher picked since then
 * is a deliberate choice and must not be offered again on every connection.
 *
 * @return null when the TV is as TV Slim left it.
 */
fun Catalog.driftPlan(
    journal: List<JournalAction>,
    states: Map<String, PackageState>,
    info: DeviceInfo,
): ReinjectionPlan? {
    val appliedHome = journal.lastHome()
    val wanted = TvConfiguration(
        application = TvConfiguration.APPLICATION,
        format = TvConfiguration.FORMAT,
        savedAt = 0,
        home = appliedHome?.let { SavedHome(packageName = it.substringBefore('/'), component = it) },
        disabled = journal.disabledPackages(),
    )
    val plan = wanted.buildPlan(this, states, info)
    val home = plan.home?.takeIf { it.possible && info.homeFellBack() }
    // Missing or unknown packages have not drifted; a backup plan lists them, this one does not.
    return plan.copy(home = home, ignores = emptyList()).takeUnless { it.nothingToDo }
}

/**
 * True if the home screen fell back to the stock one, or to Android's chooser (shown when two home
 * screens compete). A blank value is a failed read, not drift.
 */
private fun DeviceInfo.homeFellBack(): Boolean =
    currentHome == "android" || factoryHomes.any { it.packageName == currentHome }
