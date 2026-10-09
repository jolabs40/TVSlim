package net.jolabs40.tvslim.applicationtv

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
data class PublicationTv(
    val version: String,
    val versionCode: Long,
    val nomFichier: String,
    val url: String,
    val taille: Long,
)

/** GitHub, or a fake in tests. */
interface SourcePublications {
    /** JSON of `GET /repos/{repo}/releases`. */
    suspend fun publications(): String

    /** Downloads [url] to [cible], failing beyond [tailleMax] bytes. */
    suspend fun telecharger(url: String, cible: File, tailleMax: Long, progression: (recus: Long, total: Long) -> Unit)
}

/** Thrown when a download is larger than any TV app should be; reading stops there. */
class TelechargementTropGros(val taille: Long) : IOException("Fichier trop gros : $taille octets")

object ChoixPublicationTv {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Publication(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Fichier> = emptyList(),
    )

    @Serializable
    private data class Fichier(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
    )

    private val TAG = Regex("""android-v(\d{1,3})\.(\d{1,2})\.(\d{1,2})""")

    /**
     * Picks the highest `android-vX.Y.Z` release (no draft, no prerelease) that carries `TVSlim-TV-X.Y.Z.apk`.
     * The download URL must point to this repository's own release downloads; the API response is not trusted.
     */
    fun choisir(reponse: String, depot: String = DEPOT): PublicationTv? {
        val publications = runCatching { json.decodeFromString<List<Publication>>(reponse) }.getOrNull() ?: return null
        val prefixe = "https://github.com/$depot/releases/download/"
        return publications
            .asSequence()
            .filter { !it.draft && !it.prerelease }
            .mapNotNull { p ->
                val (x, y, z) = TAG.matchEntire(p.tag)?.destructured ?: return@mapNotNull null
                val version = "$x.$y.$z"
                val apk = p.assets.firstOrNull { it.name == "TVSlim-TV-$version.apk" } ?: return@mapNotNull null
                val sur = apk.url.startsWith(prefixe) && listOf("..", "?", "#").none { it in apk.url }
                if (!sur) return@mapNotNull null
                PublicationTv(version, versionCode(x.toInt(), y.toInt(), z.toInt()), apk.name, apk.url, apk.size)
            }
            .maxByOrNull { it.versionCode }
    }

    /** Same formula as the build (`TVSlim/build.gradle.kts`): X * 10000 + Y * 100 + Z. */
    fun versionCode(majeur: Int, mineur: Int, correctif: Int): Long = majeur * 10_000L + mineur * 100L + correctif

    const val DEPOT = "jolabs40/TVSlim"
}

/** Fetches the release list and the APK from GitHub, HTTPS only. `HttpURLConnection` works on Android and the JVM. */
class SourceGithub(
    /** User-Agent such as `TVSlim/1.1.0`; GitHub requires one. */
    private val agent: String,
    private val depot: String = ChoixPublicationTv.DEPOT,
) : SourcePublications {

    override suspend fun publications(): String = withContext(Dispatchers.IO) {
        val connexion = ouvrir("https://api.github.com/repos/$depot/releases?per_page=30")
        connexion.setRequestProperty("Accept", "application/vnd.github+json")
        connexion.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        try {
            if (connexion.responseCode != 200) throw IOException("GitHub : HTTP ${connexion.responseCode}")
            val tampon = ByteArrayOutputStream()
            connexion.inputStream.use { copier(it, tampon, TAILLE_MAX_LISTE, total = -1) { _, _ -> } }
            tampon.toString(Charsets.UTF_8.name())
        } finally {
            connexion.disconnect()
        }
    }

    override suspend fun telecharger(
        url: String,
        cible: File,
        tailleMax: Long,
        progression: (recus: Long, total: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // GitHub redirects to its storage. HttpURLConnection follows, but never from HTTPS to HTTP.
        val connexion = ouvrir(url)
        try {
            if (connexion.responseCode != 200) throw IOException("Téléchargement : HTTP ${connexion.responseCode}")
            if (connexion.url.protocol != "https") throw IOException("Téléchargement redirigé hors HTTPS")
            val annoncee = connexion.contentLengthLong
            if (annoncee > tailleMax) throw TelechargementTropGros(annoncee)
            cible.parentFile?.mkdirs()
            cible.outputStream().use { sortie ->
                connexion.inputStream.use { copier(it, sortie, tailleMax, annoncee, progression) }
            }
        } catch (erreur: Throwable) {
            cible.delete()
            throw erreur
        } finally {
            connexion.disconnect()
        }
    }

    private fun ouvrir(adresse: String): HttpURLConnection {
        val url = URL(adresse)
        if (url.protocol != "https") throw IOException("Adresse non HTTPS : $adresse")
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", agent)
        }
    }

    private suspend fun copier(
        entree: InputStream,
        sortie: OutputStream,
        tailleMax: Long,
        total: Long,
        progression: (Long, Long) -> Unit,
    ) {
        val bloc = ByteArray(64 * 1024)
        var recus = 0L
        while (true) {
            coroutineContext.ensureActive()
            val lus = entree.read(bloc)
            if (lus < 0) break
            recus += lus
            if (recus > tailleMax) throw TelechargementTropGros(recus)
            sortie.write(bloc, 0, lus)
            progression(recus, total)
        }
    }

    private companion object {
        /** Thirty releases with their assets fit in a few hundred KB. */
        const val TAILLE_MAX_LISTE = 4L * 1024 * 1024
    }
}
