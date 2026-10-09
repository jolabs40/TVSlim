package net.jolabs40.tvslim.device

/** An app and the internal storage it uses, in bytes. */
data class StockageApplication(
    val paquet: String,
    val applicationOctets: Long,
    val donneesOctets: Long,
    val cacheOctets: Long,
) {
    val totalOctets: Long get() = applicationOctets + donneesOctets + cacheOctets
}

/**
 * Internal storage usage as reported by `dumpsys diskstats`.
 *
 * Free and total space are current. Per-app sizes come from Android's last estimate, recomputed about once
 * a day, so they can be stale.
 */
data class RepartitionStockage(
    val totalKo: Long = 0,
    val libreKo: Long = 0,
    val applicationsOctets: Long = 0,
    val donneesOctets: Long = 0,
    val cacheOctets: Long = 0,
    val photosOctets: Long = 0,
    val videosOctets: Long = 0,
    val audioOctets: Long = 0,
    val telechargementsOctets: Long = 0,
    val autresOctets: Long = 0,
    /** Largest first. */
    val applications: List<StockageApplication> = emptyList(),
) {
    val renseignee: Boolean get() = totalKo > 0
    val utiliseKo: Long get() = (totalKo - libreKo).coerceAtLeast(0)
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
object LectureStockage {

    /** Separates `dumpsys diskstats` output from `df` output. */
    const val MARQUEUR_DF = "@@TVSLIM_DF"

    fun interpreter(sortie: String): RepartitionStockage {
        val lignes = sortie.lineSequence().map { it.trim() }.toList()
        val avantDf = lignes.takeWhile { it != MARQUEUR_DF }

        fun taille(cle: String): Long =
            avantDf.firstOrNull { it.startsWith("$cle:") }?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L

        fun liste(cle: String): List<String> =
            avantDf.firstOrNull { it.startsWith("$cle:") }
                ?.substringAfter(':')?.trim()
                ?.removePrefix("[")?.removeSuffix("]")
                ?.split(',')
                ?.map { it.trim().removeSurrounding("\"") }
                ?.filter { it.isNotEmpty() }
                .orEmpty()

        val (libre, total) = LIBRE_DATA.find(avantDf.joinToString("\n"))
            ?.let { it.groupValues[1].toLong() to it.groupValues[2].toLong() }
            ?: df(lignes.dropWhile { it != MARQUEUR_DF })
            ?: (0L to 0L)

        val paquets = liste("Package Names")
        val applications = liste("App Sizes").map { it.toLongOrNull() ?: 0L }
        val donnees = liste("App Data Sizes").map { it.toLongOrNull() ?: 0L }
        val caches = liste("Cache Sizes").map { it.toLongOrNull() ?: 0L }

        return RepartitionStockage(
            totalKo = total,
            libreKo = libre,
            applicationsOctets = taille("App Size"),
            donneesOctets = taille("App Data Size"),
            cacheOctets = taille("App Cache Size"),
            photosOctets = taille("Photos Size"),
            videosOctets = taille("Videos Size"),
            audioOctets = taille("Audio Size"),
            telechargementsOctets = taille("Downloads Size"),
            autresOctets = taille("Other Size"),
            // Parallel lists: if their lengths differ, pairing them would give one app another's
            // size, so list nothing.
            applications = if (paquets.isNotEmpty() && applications.size == paquets.size) {
                paquets.mapIndexed { rang, paquet ->
                    StockageApplication(
                        paquet = paquet,
                        applicationOctets = applications[rang],
                        donneesOctets = donnees.getOrElse(rang) { 0L },
                        cacheOctets = caches.getOrElse(rang) { 0L },
                    )
                }.sortedByDescending { it.totalOctets }
            } else {
                emptyList()
            },
        )
    }

    /** `/dev/block/dm-27  51170024 5911412 45111156  12% /data`: available and total, in KB. */
    private fun df(lignes: List<String>): Pair<Long, Long>? = lignes
        .map { it.split(ESPACES) }
        .firstOrNull { it.size >= 6 && it[1].toLongOrNull() != null && it[3].toLongOrNull() != null }
        ?.let { it[3].toLong() to it[1].toLong() }

    private val LIBRE_DATA = Regex("""Data-Free:\s*(\d+)K\s*/\s*(\d+)K""")
    private val ESPACES = Regex("""\s+""")
}
