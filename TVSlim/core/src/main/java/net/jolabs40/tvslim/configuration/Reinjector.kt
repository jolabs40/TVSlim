package net.jolabs40.tvslim.configuration

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo
import net.jolabs40.tvslim.engine.DebloatEngine
import net.jolabs40.tvslim.engine.ActionResult

/**
 * Reapplies a configuration in the only safe order, always through the engine, so its safeguards
 * apply and every action is journaled with its undo command.
 *
 *  1. Re-enable first: nothing is removed before what must come back is back.
 *  2. Then disable: blocklist, third-party launcher required, `setupwraith` before `launcherx`.
 *  3. Home screen last: `set-home-activity` has no effect while the stock home screen is enabled.
 */
class Reinjector(private val engine: DebloatEngine) {

    suspend fun reinject(
        plan: ReinjectionPlan,
        catalog: Catalog,
        states: Map<String, PackageState>,
        info: DeviceInfo,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<ActionResult> {
        val total = plan.actionCount
        val results = mutableListOf<ActionResult>()
        onProgress(0, total)

        if (plan.toEnable.isNotEmpty()) {
            results += engine.enable(plan.toEnable.map { it.packageName }) { done, _ ->
                onProgress(done, total)
            }
        }

        if (plan.toDisable.isNotEmpty()) {
            val alreadyDone = plan.toEnable.size
            results += engine.disable(
                entries = plan.toDisable,
                catalog = catalog,
                states = states + plan.toEnable.associate { it.packageName to PackageState.ACTIVE },
                launchersAvailable = info.thirdPartyLaunchers.isNotEmpty(),
                onProgress = { done, _ -> onProgress(alreadyDone + done, total) },
            )
        }

        plan.home?.takeIf { it.possible }?.let { home ->
            // If the current home component is unknown, undo sets the same one again, which is harmless.
            results += engine.setHome(
                component = home.component,
                oldHome = info.homeComponent.ifBlank { home.component },
            )
        }

        onProgress(total, total)
        return results
    }
}
