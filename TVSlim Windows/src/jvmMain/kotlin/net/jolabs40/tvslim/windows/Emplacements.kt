package net.jolabs40.tvslim.windows

import java.io.File

/**
 * Où l'application range ce qu'elle garde d'une session à l'autre.
 *
 * Rien dans le dossier d'installation : une mise à jour le remplace, une désinstallation
 * l'efface, et le journal — ce qui rend chaque intervention réversible — doit survivre aux deux.
 */
class Emplacements(
    /** Données de l'utilisateur : journaux, mesures, clé ADB, préférences, traces. */
    val donnees: File,
    /** Fichiers jetables : les installateurs téléchargés par la mise à jour. */
    val local: File,
) {
    val journaux = File(donnees, "journaux")
    val mesures = File(donnees, "mesures")
    val cles = File(donnees, "cles")
    val preferences = File(donnees, "preferences.json")
    val traces = File(donnees, "traces.log")
    val telechargements = File(local, "mises-a-jour")

    companion object {
        private const val NOM = "TVSlim"

        /** `%APPDATA%\TVSlim` pour les données, `%LOCALAPPDATA%\TVSlim` pour les téléchargements. */
        fun windows(): Emplacements {
            val maison = System.getProperty("user.home")
            val itinerant = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: File(maison, "AppData/Roaming").path
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: File(maison, "AppData/Local").path
            return Emplacements(File(itinerant, NOM), File(local, NOM))
        }
    }
}
