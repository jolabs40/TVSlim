package net.jolabs40.tvslim.tvapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** A published TV app release and its APK. */
data class TvRelease(
    val version: String,
    val versionCode: Long,
    val fileName: String,
    val url: String,
    val size: Long,
)

/** GitHub, or a fake in tests. */
interface ReleaseSource {
    /** JSON of `GET /repos/{repo}/releases`. */
    suspend fun releases(): String

    /** Downloads [url] to [target], failing beyond [maxSize] bytes. */
    suspend fun download(url: String, target: File, maxSize: Long, progress: (receivedBytes: Long, total: Long) -> Unit)
}

/** Thrown when a download is larger than any TV app should be; reading stops there. */
class DownloadTooLarge(val size: Long) : IOException("File too large: $size bytes")

object TvReleaseChoice {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<FileItem> = emptyList(),
    )

    @Serializable
    private data class FileItem(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    private val TAG = Regex("""android-v(\d{1,3})\.(\d{1,2})\.(\d{1,2})""")

    /**
     * Picks the highest `android-vX.Y.Z` release (no draft, no prerelease) that carries `TVSlim-TV-X.Y.Z.apk`.
     * The download URL must point to this repository's own release downloads; the API response is not trusted.
     */
    fun choose(response: String, repository: String = REPOSITORY): TvRelease? {
        val releases = runCatching { json.decodeFromString<List<Release>>(response) }.getOrNull() ?: return null
        val prefix = "https://github.com/$repository/releases/download/"
        return releases
            .asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { p ->
                val (x, y, z) = TAG.matchEntire(p.tag)?.destructured ?: return@mapNotNull null
                val version = "$x.$y.$z"
                val apk = p.assets.firstOrNull { it.name == "TVSlim-TV-$version.apk" } ?: return@mapNotNull null
                val safe = apk.url.startsWith(prefix) && listOf("..", "?", "#").none { it in apk.url }
                if (!safe) return@mapNotNull null
                TvRelease(version, versionCode(x.toInt(), y.toInt(), z.toInt()), apk.name, apk.url, apk.size)
            }
            .maxByOrNull { it.versionCode }
    }

    /** Same formula as the build (`TVSlim/build.gradle.kts`): X * 10000 + Y * 100 + Z. */
    fun versionCode(major: Int, minor: Int, patch: Int): Long = major * 10_000L + minor * 100L + patch

    const val REPOSITORY = "jolabs40/TVSlim"
}

/** Fetches the release list and the APK from GitHub, HTTPS only. `HttpURLConnection` works on Android and the JVM. */
class GithubSource(
    /** User-Agent such as `TVSlim/1.1.0`; GitHub requires one. */
    private val agent: String,
    private val repository: String = TvReleaseChoice.REPOSITORY,
) : ReleaseSource {

    override suspend fun releases(): String = withContext(Dispatchers.IO) {
        val connection = open("https://api.github.com/repos/$repository/releases?per_page=30")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            if (connection.responseCode != 200) throw IOException("GitHub: HTTP ${connection.responseCode}")
            val buffer = ByteArrayOutputStream()
            connection.inputStream.use { copy(it, buffer, MAX_LIST_SIZE, total = -1) { _, _ -> } }
            buffer.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun download(
        url: String,
        target: File,
        maxSize: Long,
        progress: (receivedBytes: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // GitHub redirects to its storage. HttpURLConnection follows, but never from HTTPS to HTTP.
        val connection = open(url)
        try {
            if (connection.responseCode != 200) throw IOException("Download: HTTP ${connection.responseCode}")
            if (connection.url.protocol != "https") throw IOException("Download redirected outside HTTPS")
            val announced = connection.contentLengthLong
            if (announced > maxSize) throw DownloadTooLarge(announced)
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                connection.inputStream.use { copy(it, output, maxSize, announced, progress) }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(address: String): HttpURLConnection {
        val url = URL(address)
        if (url.protocol != "https") throw IOException("Not an HTTPS address: $address")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", agent)
        }
    }

    private suspend fun copy(
        entry: InputStream,
        output: OutputStream,
        maxSize: Long,
        total: Long,
        progress: (Long, Long) -> Unit,
    ) {
        val block = ByteArray(64 * 1024)
        var receivedBytes = 0L
        while (true) {
            coroutineContext.ensureActive()
            val readResult = entry.read(block)
            if (readResult < 0) break
            receivedBytes += readResult
            if (receivedBytes > maxSize) throw DownloadTooLarge(receivedBytes)
            output.write(block, 0, readResult)
            progress(receivedBytes, total)
        }
    }

    private companion object {
        /** Thirty releases with their assets fit in a few hundred KB. */
        const val MAX_LIST_SIZE = 4L * 1024 * 1024
    }
}
