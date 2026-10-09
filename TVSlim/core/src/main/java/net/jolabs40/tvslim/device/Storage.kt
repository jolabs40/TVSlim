package net.jolabs40.tvslim.device

/** An app and the internal storage it uses, in bytes. */
data class ApplicationStorage(
    val packageName: String,
    val applicationBytes: Long,
    val dataBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = applicationBytes + dataBytes + cacheBytes
}

/**
 * Internal storage usage as reported by `dumpsys diskstats`.
 *
 * Free and total space are current. Per-app sizes come from Android's last estimate, recomputed about once
 * a day, so they can be stale.
 */
data class StorageBreakdown(
    val totalKb: Long = 0,
    val freeKb: Long = 0,
    val applicationsBytes: Long = 0,
    val dataBytes: Long = 0,
    val cacheBytes: Long = 0,
    val photosBytes: Long = 0,
    val videosBytes: Long = 0,
    val audioBytes: Long = 0,
    val downloadsBytes: Long = 0,
    val otherBytes: Long = 0,
    /** Largest first. */
    val applications: List<ApplicationStorage> = emptyList(),
) {
    val populated: Boolean get() = totalKb > 0
    val usedKb: Long get() = (totalKb - freeKb).coerceAtLeast(0)
}

/**
 * Parses `dumpsys diskstats` output, followed by `df` output for devices without a `Data-Free` line.
 * Sample from the TCL:
 *
 *     Data-Free: 45111156K / 51170024K total = 88% free
 *     App Size: 2664341504
 *     Package Names: ["com.android.cts.priv.ctsshim", …]
 *     App Sizes: [4096, …]
 */
object StorageReading {

    /** Separates `dumpsys diskstats` output from `df` output. */
    const val DF_MARKER = "@@TVSLIM_DF"

    fun parse(output: String): StorageBreakdown {
        val lines = output.lineSequence().map { it.trim() }.toList()
        val beforeDf = lines.takeWhile { it != DF_MARKER }

        fun size(key: String): Long =
            beforeDf.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L

        fun list(key: String): List<String> =
            beforeDf.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()
                ?.removePrefix("[")?.removeSuffix("]")
                ?.split(',')
                ?.map { it.trim().removeSurrounding("\"") }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        val (free, total) = DATA_FREE.find(beforeDf.joinToString("\n"))
            ?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }
            ?: df(lines.dropWhile { it != DF_MARKER })
            ?: (0L to 0L)

        val packages = list("Package Names")
        val applications = list("App Sizes").map { it.toLongOrNull() ?: 0L }
        val data = list("App Data Sizes").map { it.toLongOrNull() ?: 0L }
        val caches = list("Cache Sizes").map { it.toLongOrNull() ?: 0L }

        return StorageBreakdown(
            totalKb = total,
            freeKb = free,
            applicationsBytes = size("App Size"),
            dataBytes = size("App Data Size"),
            cacheBytes = size("App Cache Size"),
            photosBytes = size("Photos Size"),
            videosBytes = size("Videos Size"),
            audioBytes = size("Audio Size"),
            downloadsBytes = size("Downloads Size"),
            otherBytes = size("Other Size"),
            // Parallel lists: if their lengths differ, pairing them would give one app another's
            // size, so list nothing.
            applications = if (packages.isNotEmpty() && applications.size == packages.size) {
                packages.mapIndexed { index, packageName ->
                    ApplicationStorage(
                        packageName = packageName,
                        applicationBytes = applications[index],
                        dataBytes = data.getOrElse(index) { 0L },
                        cacheBytes = caches.getOrElse(index) { 0L },
                    )
                }.sortedByDescending { it.totalBytes }
            } else {
                emptyList()
            },
        )
    }

    /** `/dev/block/dm-27  51170024 5911412 45111156  12% /data`: available and total, in KB. */
    private fun df(lines: List<String>): Pair<Long, Long>? = lines
        .map { it.split(WHITESPACE) }
        .firstOrNull { it.size >= 6 && it[1].toLongOrNull() != null && it[3].toLongOrNull() != null }
        ?.let { it[3].toLong() to it[1].toLong() }

    private val DATA_FREE = Regex("""Data-Free:\s*(\d+)K\s*/\s*(\d+)K""")
    private val WHITESPACE = Regex("""\s+""")
}
