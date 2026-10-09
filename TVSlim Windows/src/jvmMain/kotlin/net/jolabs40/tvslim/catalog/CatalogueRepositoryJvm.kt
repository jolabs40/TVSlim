package net.jolabs40.tvslim.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Windows replacement for the core `CatalogueRepository`: same catalogue, English default and language overlay,
 * read from the classpath instead of Android assets (see `build.gradle.kts`).
 *
 * The Android file is excluded from the Windows build and this class takes its name, so the rest of the core
 * compiles unchanged.
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

    /** A language without a translation file falls back to English. */
    private fun lireOuNull(nom: String): String? = runCatching { lire(nom) }.getOrNull()

    private fun fichierDeLangue(code: String): String = "catalogue-$code.json"

    private companion object {
        const val FICHIER_BASE = "catalogue.json"
    }
}
