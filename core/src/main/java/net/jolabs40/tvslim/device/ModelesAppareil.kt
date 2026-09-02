package net.jolabs40.tvslim.device

/** État d'un paquet du catalogue sur un téléviseur donné. */
enum class EtatPaquet { ABSENT, ACTIF, DESACTIVE }

/** Photographie d'un téléviseur, affichée avant et après une intervention. */
data class InfosAppareil(
    val marque: String = "",
    val modele: String = "",
    val versionAndroid: String = "",
    val build: String = "",
    val memoireTotaleMo: Long = 0,
    val memoireLibreMo: Long = 0,
    val paquetsInstalles: Int = 0,
    val paquetsDesactives: Int = 0,
    val accueilActuel: String = "",
    val launchersTiers: List<LauncherInstalle> = emptyList(),
) {
    companion object {
        val VIDE = InfosAppareil()
    }
}

data class LauncherInstalle(
    val paquet: String,
    val nom: String,
    val composant: String,
)
