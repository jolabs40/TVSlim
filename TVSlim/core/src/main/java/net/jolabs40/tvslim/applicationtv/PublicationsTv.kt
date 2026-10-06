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

/** La dernière application TV publiée : sa version, et l'APK à télécharger. */
data class PublicationTv(
    val version: String,
    val versionCode: Long,
    val nomFichier: String,
    val url: String,
    val taille: Long,
)

/** GitHub, ou ce qui le remplace dans un test. */
interface SourcePublications {
    /** Le JSON de `GET /repos/{dépôt}/releases`. */
    suspend fun publications(): String

    /** Télécharge [url] dans [cible], sans jamais dépasser [tailleMax] octets. */
    suspend fun telecharger(url: String, cible: File, tailleMax: Long, progression: (recus: Long, total: Long) -> Unit)
}

/** Un fichier plus gros que ce qu'on attend d'une application TV : on cesse de le lire. */
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
     * La plus haute publication `android-vX.Y.Z` qui porte `TVSlim-TV-X.Y.Z.apk` — ni brouillon, ni
     * préversion. Le lien doit mener aux téléchargements du dépôt lui-même : la réponse de l'API se lit,
     * elle ne se croit pas sur parole.
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

    /** Le versionCode qu'en tire le build : X·10 000 + Y·100 + Z (`TVSlim/build.gradle.kts`). */
    fun versionCode(majeur: Int, mineur: Int, correctif: Int): Long = majeur * 10_000L + mineur * 100L + correctif

    const val DEPOT = "jolabs40/TVSlim"
}

/**
 * GitHub, en HTTPS et rien d'autre : la liste des publications, puis l'APK. `HttpURLConnection` existe
 * sur Android comme sous Windows : le noyau s'en sert sans dépendre de l'un ou de l'autre.
 */
class SourceGithub(
    /** « TVSlim/1.1.0 » : GitHub exige un User-Agent, autant qu'il dise qui demande. */
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
        // GitHub renvoie vers son stockage : HttpURLConnection suit, mais jamais de HTTPS vers HTTP.
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
        /** Trente publications et leurs fichiers tiennent en quelques centaines de kilo-octets. */
        const val TAILLE_MAX_LISTE = 4L * 1024 * 1024
    }
}
