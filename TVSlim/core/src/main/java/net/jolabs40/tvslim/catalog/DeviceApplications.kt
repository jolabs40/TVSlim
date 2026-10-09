package net.jolabs40.tvslim.catalog

import net.jolabs40.tvslim.device.PackageState
import net.jolabs40.tvslim.device.DeviceInfo

/** Category for phone or tablet apps that the catalogue does not describe. */
const val DEVICE_CATEGORY = "appareil"

private val IDENTIFIER = Regex("""[A-Za-z0-9_.]+""")

/**
 * On a phone or tablet, turns preinstalled apps that have a launcher icon and are unknown to the catalogue into
 * entries (YouTube, YouTube Music on a Pixel). Other unknown packages stay read-only.
 *
 * Requiring a launcher icon keeps out headless services, which may carry networking or the system UI. Each added
 * entry is untested: checked by hand only, never by a profile, still subject to the blocklist, and re-enabled from
 * the journal like any other.
 *
 * Nothing is added on a TV: an unknown system package may run the tuner or the remote, and cutting the network
 * would make ADB unreachable.
 */
fun Catalog.withMenuApps(
    info: DeviceInfo,
    systemPackages: Map<String, PackageState>,
    menu: Set<String>,
): Catalog {
    if (info.deviceType.forCatalog) return this
    val known = entries.mapTo(HashSet()) { it.packageName }
    val additions = systemPackages.keys
        .filter { it in menu && it !in known && !isProtected(it) && IDENTIFIER.matches(it) }
        .sorted()
        .map { packageName ->
            PackageEntry(
                packageName = packageName,
                // ADB cannot read an app's display name; the package name stands in.
                name = packageName,
                description = "",
                category = DEVICE_CATEGORY,
                risk = Risk.MEDIUM,
                tested = false,
            )
        }
    return if (additions.isEmpty()) this else copy(entries = entries + additions)
}
