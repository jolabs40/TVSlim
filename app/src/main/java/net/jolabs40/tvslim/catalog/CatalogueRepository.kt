package net.jolabs40.tvslim.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Charge le catalogue embarqué (`assets/catalogue.json`) : la description de chaque paquet, la
 * liste noire et les réglages système. Le fichier est la retranscription du journal
 * d'intervention mené sur un TCL 65C89K sous Android 14.
 */
@Singleton
class CatalogueRepository @Inject constructor(
    @ApplicationContext private val contexte: Context,
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
        val texte = contexte.assets.open(FICHIER).bufferedReader().use { it.readText() }
        json.decodeFromString(Catalogue.serializer(), texte)
    }

    private companion object {
        const val FICHIER = "catalogue.json"
    }
}
