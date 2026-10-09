package net.jolabs40.tvslim.windows.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlin.coroutines.coroutineContext

/**
 * GitHub client for the repository's releases and their assets. HTTPS only; redirects are followed only from
 * HTTPS to HTTPS.
 */
class GithubClient(
    private val repository: String,
    private val appVersion: String,
    private val api: String = "https://api.github.com",
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun releases(): List<GithubRelease> = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI.create("$api/repos/$repository/releases?per_page=30"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", agent())
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("GitHub : HTTP ${response.statusCode()}")
        json.decodeFromString(ListSerializer(GithubRelease.serializer()), response.body())
    }

    /** Reads a small text file (a signature) into memory, capped at [maxSize] bytes. */
    suspend fun text(url: String, maxSize: Int = 16 * 1024): String = withContext(Dispatchers.IO) {
        open(url).use { stream ->
            val buffer = ByteArrayOutputStream()
            copyBounded(stream, buffer, maxSize.toLong(), total = -1) {}
            buffer.toString(Charsets.UTF_8)
        }
    }

    /**
     * Downloads [url] to [target], aborting past [maxSize] bytes so an unexpected file cannot fill the disk.
     * Nothing appears under the final name until the download is complete.
     */
    suspend fun download(
        url: String,
        target: File,
        maxSize: Long,
        progress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val temporary = File(target.parentFile, target.name + ".part")
        try {
            val (stream, total) = openWithSize(url)
            if (total > maxSize) {
                stream.close()
                throw IOException("fichier trop volumineux ($total octets)")
            }
            stream.use { entry ->
                temporary.outputStream().buffered().use { output ->
                    copyBounded(entry, output, maxSize, total, progress)
                }
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    private suspend fun copyBounded(
        entry: InputStream,
        output: java.io.OutputStream,
        maxSize: Long,
        total: Long,
        progress: (Float) -> Unit,
    ) {
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        var lastPercentStep = -1
        while (true) {
            coroutineContext.ensureActive()
            val justRead = entry.read(buffer)
            if (justRead < 0) break
            received += justRead
            if (received > maxSize) throw IOException("fichier trop volumineux")
            output.write(buffer, 0, justRead)
            if (total > 0) {
                // Report once per percent to limit recompositions.
                val percentStep = (received * 100 / total).toInt()
                if (percentStep != lastPercentStep) {
                    lastPercentStep = percentStep
                    progress(received.toFloat() / total)
                }
            }
        }
    }

    private fun open(url: String): InputStream = openWithSize(url).first

    private fun openWithSize(url: String): Pair<InputStream, Long> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", agent())
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("téléchargement : HTTP ${response.statusCode()}")
        }
        return response.body() to response.headers().firstValueAsLong("Content-Length").orElse(-1L)
    }

    private fun agent() = "TVSlim-Windows/$appVersion"
}
