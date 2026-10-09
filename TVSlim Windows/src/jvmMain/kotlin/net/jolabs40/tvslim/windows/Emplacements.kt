package net.jolabs40.tvslim.windows

import java.io.File

/**
 * Folders for persistent data.
 *
 * Nothing goes in the install folder: an update replaces it and an uninstall deletes it, and the journal (what
 * makes every change reversible) must survive both.
 */
class Emplacements(
    /** User data: journals, measurements, ADB key, preferences, traces. */
    val donnees: File,
    /** Disposable files: downloaded installers, scrcpy if it had to be downloaded. */
    val local: File,
) {
    val journaux = File(donnees, "journaux")
    val mesures = File(donnees, "mesures")
    val cles = File(donnees, "cles")
    val preferences = File(donnees, "preferences.json")
    val traces = File(donnees, "traces.log")
    val telechargements = File(local, "mises-a-jour")
    val scrcpy = File(local, "scrcpy")

    /** App names and icons, per package and version (see `CacheApplicationsFichiers`). */
    val icones = File(local, "icones")

    companion object {
        private const val NOM = "TVSlim"

        /** `%APPDATA%\TVSlim` for data, `%LOCALAPPDATA%\TVSlim` for downloads. */
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
