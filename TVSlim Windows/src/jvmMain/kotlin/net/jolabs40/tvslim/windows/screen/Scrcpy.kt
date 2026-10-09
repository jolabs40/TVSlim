package net.jolabs40.tvslim.windows.screen

import net.jolabs40.tvslim.windows.update.GithubClient
import net.jolabs40.tvslim.windows.tools.AppLog
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * scrcpy version downloaded for mirroring when none is installed, with its published hash (`SHA256SUMS.txt` of
 * the Genymobile/scrcpy v4.1 release). Update version, folder, URL and hash together.
 */
object PinnedScrcpy {
    const val VERSION = "4.1"
    const val FOLDER = "scrcpy-win64-v4.1"
    const val URL = "https://github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-win64-v4.1.zip"
    const val SHA256 = "5b12172b3264b2889f4583ee64752ce832e29bc8b1089dca81093459697165db"

    /** The published zip is 11.3 MB; anything over 20 MB is not the expected file. */
    const val MAX_SIZE = 20_000_000L

    /** Oldest version that supports `--no-audio`. */
    val MINIMUM_VERSION = 2 to 0
}

/** scrcpy arguments. Audio stays on the TV (`--no-audio`); recording goes through `TvRecording`, not scrcpy. */
object ScrcpyArguments {
    fun mirror(host: String, port: Int, title: String): List<String> =
        listOf("--tcpip=$host:$port", "--window-title=$title", "--no-audio")
}

/**
 * Finds scrcpy: the copy TV Slim downloaded, then PATH, then a winget install. Versions too old for our options
 * are skipped, so the pinned version is offered instead of an obscure launch failure.
 */
class ScrcpyLocator(
    private val downloadFolder: File,
    private val environment: (String) -> String? = System::getenv,
    private val readVersion: (File) -> Pair<Int, Int>? = ::versionOf,
) {

    fun find(): File? = candidates().firstOrNull { exe ->
        exe.isFile && readVersion(exe)?.let { isSufficient(it) } == true
    }

    internal fun candidates(): List<File> = buildList {
        add(File(downloadFolder, "${PinnedScrcpy.FOLDER}/scrcpy.exe"))
        environment("PATH").orEmpty().split(';')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .forEach { add(File(it, "scrcpy.exe")) }
        environment("LOCALAPPDATA")?.let { local ->
            val packages = File(local, "Microsoft/WinGet/Packages")
            packages.listFiles { f -> f.isDirectory && f.name.startsWith("Genymobile.scrcpy") }.orEmpty()
                .flatMap { packageName -> packageName.listFiles { f -> f.isDirectory && f.name.startsWith("scrcpy-win64") }.orEmpty().toList() }
                .sortedByDescending { it.name }
                .forEach { add(File(it, "scrcpy.exe")) }
        }
    }.distinctBy { it.absolutePath.lowercase() }

    companion object {
        fun isSufficient(version: Pair<Int, Int>): Boolean {
            val (major, minor) = PinnedScrcpy.MINIMUM_VERSION
            return version.first > major || (version.first == major && version.second >= minor)
        }

        /** `scrcpy 4.1 <https://github.com/Genymobile/scrcpy>` gives 4 to 1. */
        fun readVersionLine(output: String): Pair<Int, Int>? =
            Regex("""scrcpy\s+(\d+)\.(\d+)""").find(output)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

        private fun versionOf(exe: File): Pair<Int, Int>? = runCatching {
            val process = ProcessBuilder(exe.absolutePath, "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            readVersionLine(output)
        }.onFailure { AppLog.warn("Scrcpy", "Version illisible", it) }.getOrNull()
    }
}

/** The downloaded archive does not match the published hash; it is deleted and nothing is installed. */
class UnexpectedFingerprint : IOException("empreinte SHA-256 inattendue")

/**
 * Downloads scrcpy from Genymobile's GitHub release, checks the hash and unzips into `%LOCALAPPDATA%\TVSlim\scrcpy`.
 * Nothing appears under the final name until every step succeeded.
 */
class ScrcpyInstallation(private val github: GithubClient, private val folder: File) {

    suspend fun install(progress: (Float) -> Unit): File {
        folder.mkdirs()
        val archive = File(folder, "${PinnedScrcpy.FOLDER}.zip")
        try {
            github.download(PinnedScrcpy.URL, archive, PinnedScrcpy.MAX_SIZE, progress)
            if (fingerprint(archive) != PinnedScrcpy.SHA256) throw UnexpectedFingerprint()
            return extract(archive)
        } finally {
            archive.delete()
        }
    }

    internal fun extract(archive: File): File {
        val temporary = File(folder, "${PinnedScrcpy.FOLDER}.extraction")
        temporary.deleteRecursively()
        temporary.mkdirs()
        val root = temporary.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(temporary, entry.name).canonicalFile
                // Zip slip: reject entries that escape the extraction folder.
                if (!target.path.startsWith(root.path + File.separator)) throw IOException("entrée refusée : ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        // The archive nests everything under `scrcpy-win64-v4.1/`; a flat archive is taken as is.
        val content = File(temporary, PinnedScrcpy.FOLDER).takeIf { File(it, "scrcpy.exe").isFile } ?: temporary
        if (!File(content, "scrcpy.exe").isFile) throw IOException("scrcpy.exe absent de l'archive")
        val final = File(folder, PinnedScrcpy.FOLDER)
        final.deleteRecursively()
        Files.move(content.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
        temporary.deleteRecursively()
        return File(final, "scrcpy.exe")
    }

    companion object {
        fun fingerprint(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val justRead = stream.read(buffer)
                    if (justRead < 0) break
                    digest.update(buffer, 0, justRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
