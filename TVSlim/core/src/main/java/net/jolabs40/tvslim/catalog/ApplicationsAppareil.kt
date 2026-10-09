package net.jolabs40.tvslim.catalog

import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil

/** Category for phone or tablet apps that the catalogue does not describe. */
const val CATEGORIE_APPAREIL = "appareil"

private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

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
fun Catalogue.avecApplicationsDuMenu(
    infos: InfosAppareil,
    paquetsSysteme: Map<String, EtatPaquet>,
    menu: Set<String>,
): Catalogue {
    if (infos.typeAppareil.pourLeCatalogue) return this
    val connus = entrees.mapTo(HashSet()) { it.paquet }
    val ajouts = paquetsSysteme.keys
        .filter { it in menu && it !in connus && !estProtege(it) && IDENTIFIANT.matches(it) }
        .sorted()
        .map { paquet ->
            EntreePaquet(
                paquet = paquet,
                // ADB cannot read an app's display name; the package name stands in.
                nom = paquet,
                description = "",
                categorie = CATEGORIE_APPAREIL,
                risque = Risque.MOYEN,
                eprouve = false,
            )
        }
    return if (ajouts.isEmpty()) this else copy(entrees = entrees + ajouts)
}
