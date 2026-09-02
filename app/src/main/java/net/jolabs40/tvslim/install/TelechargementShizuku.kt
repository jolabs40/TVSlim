package net.jolabs40.tvslim.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * Récupère l'APK de Shizuku depuis les publications du dépôt officiel.
 *
 * Les redirections sont suivies à la main plutôt que par la pile HTTP : c'est le seul moyen de
 * contrôler l'hôte à chaque saut et de refuser qu'un renvoi mène ailleurs que chez GitHub.
 * Tout ce qui n'est pas HTTPS est rejeté d'emblée.
 */
@Singleton
class TelechargementShizuku @Inject constructor(
    @ApplicationContext private val contexte: Context,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Interroge l'API des publications et renvoie l'APK de la dernière version. */
    suspend fun dernierApk(): AssetGitHub = withContext(Dispatchers.IO) {
        val connexion = ouvrir(SourceShizuku.API_DERNIERE_VERSION)
        val corps = try {
            connexion.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connexion.disconnect()
        }
        val release = json.decodeFromString(ReleaseGitHub.serializer(), corps)
        release.apk() ?: throw IOException("Aucun APK dans la publication ${release.tag}.")
    }

    /** Télécharge l'APK dans le cache de l'application et renvoie le fichier obtenu. */
    suspend fun telecharger(
        asset: AssetGitHub,
        surProgression: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (asset.size > SourceShizuku.TAILLE_MAX_OCTETS) {
            throw IOException("Fichier annoncé à ${asset.size / (1024 * 1024)} Mo : refusé.")
        }
        val cible = File(contexte.cacheDir, SourceShizuku.NOM_FICHIER)
        cible.delete()

        val connexion = ouvrir(asset.url)
        try {
            val total = connexion.contentLengthLong.takeIf { it > 0 } ?: asset.size
            var recu = 0L
            connexion.inputStream.use { entree ->
                cible.outputStream().use { sortie ->
                    val tampon = ByteArray(TAILLE_TAMPON)
                    while (true) {
                        coroutineContext.ensureActive()
                        val lus = entree.read(tampon)
                        if (lus < 0) break
                        sortie.write(tampon, 0, lus)
                        recu += lus
                        if (recu > SourceShizuku.TAILLE_MAX_OCTETS) {
                            throw IOException("Téléchargement anormalement volumineux : interrompu.")
                        }
                        if (total > 0) surProgression((recu * 100 / total).toInt())
                    }
                }
            }
            if (total > 0 && recu != total) {
                throw IOException("Téléchargement incomplet ($recu / $total octets).")
            }
        } catch (erreur: Throwable) {
            cible.delete()
            throw erreur
        } finally {
            connexion.disconnect()
        }
        cible
    }

    private fun ouvrir(depart: String): HttpsURLConnection {
        var courante = URL(depart)
        repeat(MAX_REDIRECTIONS) {
            verifier(courante)
            val connexion = courante.openConnection() as? HttpsURLConnection
                ?: throw IOException("Connexion non chiffrée refusée.")
            connexion.instanceFollowRedirects = false
            connexion.connectTimeout = DELAI_CONNEXION_MS
            connexion.readTimeout = DELAI_LECTURE_MS
            connexion.setRequestProperty("User-Agent", AGENT)
            connexion.setRequestProperty("Accept", "application/vnd.github+json")

            when (val code = connexion.responseCode) {
                in 200..299 -> return connexion

                in 300..399 -> {
                    val destination = connexion.getHeaderField("Location")
                    connexion.disconnect()
                    if (destination.isNullOrBlank()) throw IOException("Redirection sans destination.")
                    courante = URL(courante, destination)
                }

                else -> {
                    connexion.disconnect()
                    throw IOException("Le serveur a répondu $code.")
                }
            }
        }
        throw IOException("Trop de redirections.")
    }

    private fun verifier(url: URL) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Adresse non chiffrée refusée : ${url.protocol}")
        }
        if (url.host !in SourceShizuku.HOTES_ADMIS) {
            throw IOException("Hôte non autorisé : ${url.host}")
        }
    }

    private companion object {
        const val MAX_REDIRECTIONS = 5
        const val TAILLE_TAMPON = 16 * 1024
        const val DELAI_CONNEXION_MS = 15_000
        const val DELAI_LECTURE_MS = 30_000
        const val AGENT = "TVSlim"
    }
}
