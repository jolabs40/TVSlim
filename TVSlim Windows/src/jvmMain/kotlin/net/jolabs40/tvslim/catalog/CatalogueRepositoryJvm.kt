package net.jolabs40.tvslim.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Pendant Windows du `CatalogueRepository` du noyau : même catalogue, même anglais par défaut,
 * même surcharge de langue. Seule la lecture change — Android ouvre ses assets par un `Context`,
 * ici le build range ces mêmes fichiers dans le classpath (voir `build.gradle.kts`).
 *
 * Le fichier Android est exclu de la compilation Windows et celui-ci le remplace sous le même
 * nom de classe : le reste du noyau, compilé depuis les sources d'Android, n'y voit aucune
 * différence.
 */
class CatalogueRepository(
    private val langue: () -> String = { Locale.getDefault().language },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val verrou = Mutex()
    private var cache: Catalogue? = null

    suspend fun catalogue(): Catalogue = verrou.withLock {
        cache ?: charger().also { cache = it }
    }

    private suspend fun charger(): Catalogue = withContext(Dispatchers.IO) {
        val base = json.decodeFromString(Catalogue.serializer(), lire(FICHIER_BASE))
        val surcharge = lireOuNull(fichierDeLangue(langue()))
            ?: return@withContext base
        base.traduit(json.decodeFromString(Traductions.serializer(), surcharge))
    }

    private fun lire(nom: String): String {
        val flux = CatalogueRepository::class.java.classLoader.getResourceAsStream(nom)
            ?: throw FileNotFoundException(nom)
        return flux.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Une langue sans fichier de traduction n'est pas une erreur : l'anglais fait office. */
    private fun lireOuNull(nom: String): String? = runCatching { lire(nom) }.getOrNull()

    private fun fichierDeLangue(code: String): String = "catalogue-$code.json"

    private companion object {
        const val FICHIER_BASE = "catalogue.json"
    }
}
