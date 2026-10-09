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

/** Snapshot of a TV at a given time, reduced to what can be compared between snapshots. */
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
 * What is kept for a TV: its state at first contact, and the latest one. Two snapshots are enough
 * to tell what the debloat changed; a full history would only add noise.
 */
@Serializable
data class HistoriqueMesures(
    val reference: Mesure? = null,
    val derniere: Mesure? = null,
) {
    /** The gain only makes sense between two distinct snapshots. */
    val comparable: Boolean
        get() = reference != null && derniere != null &&
            reference.horodatage != derniere.horodatage

    val paquetsDesactivesEnPlus: Int
        get() = if (comparable) derniere!!.paquetsDesactives - reference!!.paquetsDesactives else 0

    val memoireLibreEnPlusMo: Long
        get() = if (comparable) derniere!!.memoireLibreMo - reference!!.memoireLibreMo else 0
}

/**
 * Before/after snapshots of a TV, persisted across sessions.
 *
 * The journal says what was done; this says what it achieved. The first recorded snapshot is the
 * baseline (the TV before anything was touched) and is not re-measured once debloating started.
 *
 * One file per TV, like the journal.
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
     * Records the current state. The very first snapshot becomes the baseline, so on a TV debloated
     * the day before, the "before" is not refreshed and the gain is not erased.
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

    /** Makes the latest snapshot the new baseline, to start over. */
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
