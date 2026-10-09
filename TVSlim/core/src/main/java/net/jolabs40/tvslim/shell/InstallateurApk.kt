package net.jolabs40.tvslim.shell

import java.io.File

/**
 * Pushes an APK to a TV and installs it.
 *
 * Separate from [ExecuteurCommande]: a shell command is one line and safe to replay, a transfer of tens
 * of megabytes is not. Each app's ADB connection implements both interfaces.
 */
interface InstallateurApk {
    /**
     * Pushes and installs [apk] like `adb install -r -t`: an installed app is updated and keeps its data.
     * [surEnvoi] reports the bytes sent.
     *
     * Returns `Success`, otherwise Android's answer (`Failure [INSTALL_...]`), or
     * [ResultatShell.indisponible] if the connection dropped.
     */
    suspend fun installer(apk: File, surEnvoi: (envoye: Long, total: Long) -> Unit): ResultatShell
}
