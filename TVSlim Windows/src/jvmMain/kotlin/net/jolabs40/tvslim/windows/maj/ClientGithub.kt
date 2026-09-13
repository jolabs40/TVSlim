package net.jolabs40.tvslim.windows.maj

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
 * Parle à GitHub, et à lui seul : la liste des publications du dépôt, puis les fichiers qui y
 * sont attachés. HTTPS partout, redirections suivies seulement de HTTPS en HTTPS.
 */
class ClientGithub(
    private val depot: String,
    private val versionApp: String,
    private val api: String = "https://api.github.com",
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun publications(): List<PublicationGithub> = withContext(Dispatchers.IO) {
        val requete = HttpRequest.newBuilder(URI.create("$api/repos/$depot/releases?per_page=30"))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", agent())
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val reponse = http.send(requete, HttpResponse.BodyHandlers.ofString())
        if (reponse.statusCode() != 200) throw IOException("GitHub : HTTP ${reponse.statusCode()}")
        json.decodeFromString(ListSerializer(PublicationGithub.serializer()), reponse.body())
    }

    /** Un petit fichier texte — une signature — lu en mémoire, borné à [tailleMax] octets. */
    suspend fun texte(url: String, tailleMax: Int = 16 * 1024): String = withContext(Dispatchers.IO) {
        ouvrir(url).use { flux ->
            val tampon = ByteArrayOutputStream()
            copierBorne(flux, tampon, tailleMax.toLong(), total = -1) {}
            tampon.toString(Charsets.UTF_8)
        }
    }

    /**
     * Télécharge [url] dans [cible], sans jamais dépasser [tailleMax] : un fichier plus gros que
     * prévu est abandonné avant d'emplir le disque. Rien n'apparaît sous le nom final tant que le
     * téléchargement n'est pas complet.
     */
    suspend fun telecharger(
        url: String,
        cible: File,
        tailleMax: Long,
        progression: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val provisoire = File(cible.parentFile, cible.name + ".part")
        try {
            val (flux, total) = ouvrirAvecTaille(url)
            if (total > tailleMax) {
                flux.close()
                throw IOException("fichier trop volumineux ($total octets)")
            }
            flux.use { entree ->
                provisoire.outputStream().buffered().use { sortie ->
                    copierBorne(entree, sortie, tailleMax, total, progression)
                }
            }
            Files.move(
                provisoire.toPath(),
                cible.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            provisoire.delete()
        }
    }

    private suspend fun copierBorne(
        entree: InputStream,
        sortie: java.io.OutputStream,
        tailleMax: Long,
        total: Long,
        progression: (Float) -> Unit,
    ) {
        val tampon = ByteArray(64 * 1024)
        var recu = 0L
        var dernierPalier = -1
        while (true) {
            coroutineContext.ensureActive()
            val lu = entree.read(tampon)
            if (lu < 0) break
            recu += lu
            if (recu > tailleMax) throw IOException("fichier trop volumineux")
            sortie.write(tampon, 0, lu)
            if (total > 0) {
                // Un palier par pour-cent : pas mille recompositions pour un seul téléchargement.
                val palier = (recu * 100 / total).toInt()
                if (palier != dernierPalier) {
                    dernierPalier = palier
                    progression(recu.toFloat() / total)
                }
            }
        }
    }

    private fun ouvrir(url: String): InputStream = ouvrirAvecTaille(url).first

    private fun ouvrirAvecTaille(url: String): Pair<InputStream, Long> {
        val requete = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", agent())
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        val reponse = http.send(requete, HttpResponse.BodyHandlers.ofInputStream())
        if (reponse.statusCode() != 200) {
            reponse.body().close()
            throw IOException("téléchargement : HTTP ${reponse.statusCode()}")
        }
        return reponse.body() to reponse.headers().firstValueAsLong("Content-Length").orElse(-1L)
    }

    private fun agent() = "TVSlim-Windows/$versionApp"
}
