package net.jolabs40.tvslim.catalog

import net.jolabs40.tvslim.device.EtatPaquet
import net.jolabs40.tvslim.device.InfosAppareil

/** La catégorie des applications d'un téléphone ou d'une tablette que le catalogue ne décrit pas. */
const val CATEGORIE_APPAREIL = "appareil"

private val IDENTIFIANT = Regex("""[A-Za-z0-9_.]+""")

/**
 * Sur un téléphone ou une tablette, les applications **préinstallées** qui ont une icône dans le menu, et que le
 * catalogue ne décrit pas, deviennent des entrées — YouTube, YouTube Music sur un Pixel. Décision de l'utilisateur
 * (2026-10-04) : les paquets inconnus restent en lecture seule, sauf celles-là, hors téléviseur.
 *
 * Une application visible est une application, pas un service sans visage : la limite écarte ce qui porte le
 * réseau ou l'interface. Chacune est **non éprouvée** — elle se coche à la main, jamais par un profil, et le dit —,
 * reste soumise à la liste noire, et se réactive depuis le journal comme le reste.
 *
 * Sur un téléviseur, rien ne change : un paquet système inconnu peut y porter le tuner ou la télécommande, et
 * couper le réseau rendrait ADB injoignable.
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
                // Le nom affiché d'une application ne se lit pas par ADB : le paquet en tient lieu.
                nom = paquet,
                description = "",
                categorie = CATEGORIE_APPAREIL,
                risque = Risque.MOYEN,
                eprouve = false,
            )
        }
    return if (ajouts.isEmpty()) this else copy(entrees = entrees + ajouts)
}
