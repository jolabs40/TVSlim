package net.jolabs40.tvslim.mesure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** L'état d'un téléviseur à un instant donné, réduit à ce qui se compare d'une fois sur l'autre. */
@Serializable
data class Mesure(
    val horodatage: Long,
    val paquetsActifs: Int,
    val paquetsDesactives: Int,
    val memoireTotaleMo: Long,
    val memoireLibreMo: Long,
) {
    val renseignee: Boolean get() = paquetsActifs > 0 || memoireTotaleMo > 0
}

/**
 * Ce qu'on garde d'un téléviseur : son état au tout premier contact, et le plus récent.
 *
 * Deux mesures suffisent à répondre à la seule question qui intéresse — « qu'est-ce que ça a
 * changé ? ». Garder tout l'historique donnerait une courbe joliment bruitée, pas une réponse.
 */
@Serializable
data class HistoriqueMesures(
    val reference: Mesure? = null,
    val derniere: Mesure? = null,
) {
    /** Le gain n'a de sens qu'entre deux mesures distinctes. */
    val comparable: Boolean
        get() = reference != null && derniere != null &&
            reference.horodatage != derniere.horodatage

    val paquetsDesactivesEnPlus: Int
        get() = if (comparable) derniere!!.paquetsDesactives - reference!!.paquetsDesactives else 0

    val memoireLibreEnPlusMo: Long
        get() = if (comparable) derniere!!.memoireLibreMo - reference!!.memoireLibreMo else 0
}

/**
 * Mesures avant / après d'un téléviseur, conservées d'une session à l'autre.
 *
 * Le journal dit ce qui a été fait ; ceci dit ce que ça a donné. La première mesure enregistrée
 * fait office de référence : c'est l'état du téléviseur avant qu'on y touche, et il ne sert à
 * rien de le remesurer une fois le débloat commencé.
 *
 * Un fichier par téléviseur, comme le journal.
 */
class MesuresRepository(private val fichier: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serialiseur = HistoriqueMesures.serializer()
    private val verrou = Mutex()

    private val _historique = MutableStateFlow(HistoriqueMesures())
    val historique: StateFlow<HistoriqueMesures> = _historique.asStateFlow()

    suspend fun charger() = withContext(Dispatchers.IO) {
        verrou.withLock {
            _historique.value = if (fichier.exists()) {
                runCatching { json.decodeFromString(serialiseur, fichier.readText()) }
                    .getOrDefault(HistoriqueMesures())
            } else {
                HistoriqueMesures()
            }
        }
    }

    /**
     * Consigne l'état courant. La toute première devient la référence : sur un téléviseur déjà
     * dégraissé la veille, on ne veut pas que le « avant » se remette à jour et efface le gain.
     */
    suspend fun enregistrer(mesure: Mesure) = withContext(Dispatchers.IO) {
        if (!mesure.renseignee) return@withContext
        verrou.withLock {
            val courant = _historique.value
            val fusion = courant.copy(
                reference = courant.reference ?: mesure,
                derniere = mesure,
            )
            _historique.value = fusion
            ecrire(fusion)
        }
    }

    /** Reprend la mesure courante comme nouvelle référence, pour repartir d'une page blanche. */
    suspend fun redefinirReference() = withContext(Dispatchers.IO) {
        verrou.withLock {
            val fusion = HistoriqueMesures(
                reference = _historique.value.derniere,
                derniere = _historique.value.derniere,
            )
            _historique.value = fusion
            ecrire(fusion)
        }
    }

    private fun ecrire(historique: HistoriqueMesures) {
        runCatching {
            fichier.parentFile?.mkdirs()
            fichier.writeText(json.encodeToString(serialiseur, historique))
        }
    }
}
