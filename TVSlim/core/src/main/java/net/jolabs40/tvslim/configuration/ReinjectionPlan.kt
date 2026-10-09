package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo

/**
 * What reapplying a configuration would change on this TV, computed before touching it so it can be
 * shown and confirmed. Only differences are listed.
 */
data class ReinjectionPlan(
    val configuration: TvConfiguration,
    /** Enabled in the backup, disabled here. */
    val toEnable: List<PackageEntry>,
    /** Disabled in the backup, enabled here. */
    val toDisable: List<PackageEntry>,
    /** Backup packages missing from this TV, or no longer in the catalogue. */
    val ignores: List<String>,
    /** Home screen from the backup, when it differs from the current one. */
    val home: HomeChange?,
) {
    val actionCount: Int
        get() = toEnable.size + toDisable.size + if (home?.possible == true) 1 else 0

    val nothingToDo: Boolean get() = actionCount == 0
}

/** Home screen to restore. [component] is blank when that launcher is missing on this TV. */
data class HomeChange(
    val packageName: String,
    val name: String,
    val component: String,
) {
    val possible: Boolean get() = component.isNotBlank()
}

/** Compares the backup with the TV as just read, without running anything. */
fun TvConfiguration.buildPlan(
    catalog: Catalog,
    states: Map<String, PackageState>,
    info: DeviceInfo,
): ReinjectionPlan {
    val entries = catalog.entries.associateBy { it.packageName }
    fun state(packageName: String) = states[packageName] ?: PackageState.ABSENT

    return ReinjectionPlan(
        configuration = this,
        toEnable = active.distinct().mapNotNull { entries[it] }
            .filter { state(it.packageName) == PackageState.DISABLED },
        // Blocklisted packages are never replayed, even from a file: the engine would refuse them, so the
        // preview does not list them.
        toDisable = disabled.distinct().mapNotNull { entries[it] }
            .filter { state(it.packageName) == PackageState.ACTIVE && !catalog.isProtected(it.packageName) },
        ignores = (active + disabled).distinct()
            .filter { it !in entries || state(it) == PackageState.ABSENT },
        home = homeChange(catalog, info),
    )
}

private fun TvConfiguration.homeChange(catalog: Catalog, info: DeviceInfo): HomeChange? {
    val wanted = home ?: return null
    if (catalog.sameLauncher(wanted.packageName, info.currentHome)) return null

    // Candidates: installed launchers and stock home screens, disabled ones included (those are
    // re-enabled before being set).
    val candidates = info.thirdPartyLaunchers.map { it.packageName to it.component } +
        info.factoryHomes.map { it.packageName to it.component }

    // Same package first, otherwise another variant of the same launcher: a backup from a development TV
    // names the .debug build.
    val found = candidates.firstOrNull { it.first == wanted.packageName }
        ?: candidates.firstOrNull { catalog.sameLauncher(wanted.packageName, it.first) }

    return HomeChange(
        packageName = found?.first ?: wanted.packageName,
        name = wanted.name.ifBlank { catalog.launcherName(wanted.packageName) ?: wanted.packageName },
        component = found?.second.orEmpty(),
    )
}

/** True if both packages belong to the same launcher: identical, or known variants of one app. */
private fun Catalog.sameLauncher(a: String, b: String): Boolean =
    a == b ||
        launchers.any { it.matches(a) && it.matches(b) } ||
        knownLaunchers.any { a in it.packages && b in it.packages }
