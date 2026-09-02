package net.jolabs40.tvslim.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Charge le catalogue embarqué : la description de chaque paquet, la liste noire et les réglages
 * système. Son contenu est la retranscription du journal d'intervention mené sur un TCL 65C89K
 * sous Android 14.
 *
 * `catalogue.json` est en anglais ; la langue du téléviseur est appliquée par-dessus depuis
 * `catalogue-<langue>.json` s'il existe. Une langue sans fichier, ou une entrée absente de la
 * traduction, retombe simplement sur l'anglais.
 *
 * Il vit dans le module partagé : le compagnon mobile et l'application du téléviseur lisent
 * exactement le même catalogue.
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

    /** Une langue sans fichier de traduction n'est pas une erreur : l'anglais fait office. */
    private fun lireOuNull(nom: String): String? = runCatching { lire(nom) }.getOrNull()

    private fun fichierDeLangue(code: String): String = "catalogue-$code.json"

    private companion object {
        const val FICHIER_BASE = "catalogue.json"
    }
}
