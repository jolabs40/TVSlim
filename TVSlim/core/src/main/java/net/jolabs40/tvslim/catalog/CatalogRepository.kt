package net.jolabs40.tvslim.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Loads the bundled catalogue: package descriptions, blocklist and system settings, transcribed from
 * the debloat log of a TCL 65C89K on Android 14.
 *
 * `catalog.json` is in English; `catalog-<lang>.json` is applied on top when it exists. A
 * missing language file or a missing entry falls back to English.
 *
 * Lives in the shared module so the phone companion and the TV app read the same catalogue.
 */
class CatalogRepository(
    private val context: Context,
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

    private fun read(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    /** A language without a translation file is not an error: English is used. */
    private fun readOrNull(name: String): String? = runCatching { read(name) }.getOrNull()

    private fun languageFile(code: String): String = "catalogue-$code.json"

    private companion object {
        const val BASE_FILE = "catalogue.json"
    }
}
