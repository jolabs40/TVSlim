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
 * `catalogue.json` is in English; `catalogue-<lang>.json` is applied on top when it exists. A
 * missing language file or a missing entry falls back to English.
 *
 * Lives in the shared module so the phone companion and the TV app read the same catalogue.
 */
class CatalogueRepository(
    private val contexte: Context,
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

    private fun lire(nom: String): String =
        contexte.assets.open(nom).bufferedReader().use { it.readText() }

    /** A language without a translation file is not an error: English is used. */
    private fun lireOuNull(nom: String): String? = runCatching { lire(nom) }.getOrNull()

    private fun fichierDeLangue(code: String): String = "catalogue-$code.json"

    private companion object {
        const val FICHIER_BASE = "catalogue.json"
    }
}
