package net.jolabs40.tvslim.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Windows replacement for the core `CatalogRepository`: same catalogue, English default and language overlay,
 * read from the classpath instead of Android assets (see `build.gradle.kts`).
 *
 * The Android file is excluded from the Windows build and this class takes its name, so the rest of the core
 * compiles unchanged.
 */
class CatalogRepository(
    private val language: () -> String = { Locale.getDefault().language },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val lock = Mutex()
    private var cache: Catalog? = null

    suspend fun catalog(): Catalog = lock.withLock {
        cache ?: load().also { cache = it }
    }

    private suspend fun load(): Catalog = withContext(Dispatchers.IO) {
        val base = json.decodeFromString(Catalog.serializer(), read(BASE_FILE))
        val languageOverride = readOrNull(languageFile(language()))
            ?: return@withContext base
        base.translated(json.decodeFromString(Translations.serializer(), languageOverride))
    }

    private fun read(name: String): String {
        val stream = CatalogRepository::class.java.classLoader.getResourceAsStream(name)
            ?: throw FileNotFoundException(name)
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** A language without a translation file falls back to English. */
    private fun readOrNull(name: String): String? = runCatching { read(name) }.getOrNull()

    private fun languageFile(code: String): String = "catalogue-$code.json"

    private companion object {
        const val BASE_FILE = "catalogue.json"
    }
}
