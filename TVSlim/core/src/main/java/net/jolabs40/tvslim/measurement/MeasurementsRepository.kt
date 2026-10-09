package net.jolabs40.tvslim.measurement

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Snapshot of a TV at a given time, reduced to what can be compared between snapshots. */
@Serializable
data class Measurement(
    @SerialName("horodatage") val timestamp: Long,
    @SerialName("paquetsActifs") val activePackages: Int,
    @SerialName("paquetsDesactives") val disabledPackages: Int,
    @SerialName("memoireTotaleMo") val totalMemoryMb: Long,
    @SerialName("memoireLibreMo") val freeMemoryMb: Long,
) {
    val populated: Boolean get() = activePackages > 0 || totalMemoryMb > 0
}

/**
 * What is kept for a TV: its state at first contact, and the latest one. Two snapshots are enough
 * to tell what the debloat changed; a full history would only add noise.
 */
@Serializable
data class MeasurementHistory(
    val reference: Measurement? = null,
    @SerialName("derniere") val last: Measurement? = null,
) {
    /** The gain only makes sense between two distinct snapshots. */
    val comparable: Boolean
        get() = reference != null && last != null &&
            reference.timestamp != last.timestamp

    val extraDisabledPackages: Int
        get() = if (comparable) last!!.disabledPackages - reference!!.disabledPackages else 0

    val extraFreeMemoryMb: Long
        get() = if (comparable) last!!.freeMemoryMb - reference!!.freeMemoryMb else 0
}

/**
 * Before/after snapshots of a TV, persisted across sessions.
 *
 * The journal says what was done; this says what it achieved. The first recorded snapshot is the
 * baseline (the TV before anything was touched) and is not re-measured once debloating started.
 *
 * One file per TV, like the journal.
 */
class MeasurementsRepository(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = MeasurementHistory.serializer()
    private val lock = Mutex()

    private val _history = MutableStateFlow(MeasurementHistory())
    val history: StateFlow<MeasurementHistory> = _history.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        lock.withLock {
            _history.value = if (file.exists()) {
                runCatching { json.decodeFromString(serializer, file.readText()) }
                    .getOrDefault(MeasurementHistory())
            } else {
                MeasurementHistory()
            }
        }
    }

    /**
     * Records the current state. The very first snapshot becomes the baseline, so on a TV debloated
     * the day before, the "before" is not refreshed and the gain is not erased.
     */
    suspend fun record(measurement: Measurement) = withContext(Dispatchers.IO) {
        if (!measurement.populated) return@withContext
        lock.withLock {
            val current = _history.value
            val merged = current.copy(
                reference = current.reference ?: measurement,
                last = measurement,
            )
            _history.value = merged
            write(merged)
        }
    }

    /** Makes the latest snapshot the new baseline, to start over. */
    suspend fun resetReference() = withContext(Dispatchers.IO) {
        lock.withLock {
            val merged = MeasurementHistory(
                reference = _history.value.last,
                last = _history.value.last,
            )
            _history.value = merged
            write(merged)
        }
    }

    private fun write(history: MeasurementHistory) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, history))
        }
    }
}
