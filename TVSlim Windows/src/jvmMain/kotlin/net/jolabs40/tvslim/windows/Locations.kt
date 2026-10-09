package net.jolabs40.tvslim.windows

import java.io.File

/**
 * Folders for persistent data.
 *
 * Nothing goes in the install folder: an update replaces it and an uninstall deletes it, and the journal (what
 * makes every change reversible) must survive both.
 */
class Locations(
    /** User data: journals, measurements, ADB key, preferences, traces. */
    val data: File,
    /** Disposable files: downloaded installers, scrcpy if it had to be downloaded. */
    val local: File,
) {
    val journals = File(data, "journaux")
    val measurements = File(data, "mesures")
    val keys = File(data, "cles")
    val preferences = File(data, "preferences.json")
    val traces = File(data, "traces.log")
    val downloads = File(local, "mises-a-jour")
    val scrcpy = File(local, "scrcpy")

    /** App names and icons, per package and version (see `FileCacheApplications`). */
    val icons = File(local, "icones")

    companion object {
        private const val NAME = "TVSlim"

        /** `%APPDATA%\TVSlim` for data, `%LOCALAPPDATA%\TVSlim` for downloads. */
        fun windows(): Locations {
            val userHome = System.getProperty("user.home")
            val roaming = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: File(userHome, "AppData/Roaming").path
            val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                ?: File(userHome, "AppData/Local").path
            return Locations(File(roaming, NAME), File(local, NAME))
        }
    }
}
