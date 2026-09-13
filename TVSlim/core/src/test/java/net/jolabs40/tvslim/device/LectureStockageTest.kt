package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le stockage d'un téléviseur, lu dans `dumpsys diskstats` — et dans `df` à défaut. */
class LectureStockageTest {

    /** Extrait de la sortie relevée sur la TCL le 2026-09-13, listes raccourcies à trois paquets. */
    private val tcl = """
        Latency: 0ms [512B Data Write]
        Recent Disk Write Speed (kB/s) = 9260
        Data-Free: 45111156K / 51170024K total = 88% free
        Cache-Free: 45111156K / 51170024K total = 88% free
        System-Free: 0K / 429360K total = 0% free
        File-based Encryption: true
        App Size: 2664341504
        App Data Size: 1880899072
        App Cache Size: 961830912
        Photos Size: 129970176
        Videos Size: 0
        Audio Size: 0
        Downloads Size: 0
        System Size: 64000000000
        Other Size: 764686336
        Package Names: ["com.tcl.esticker","com.google.android.apps.mediashell","com.google.android.katniss"]
        App Sizes: [53248,114307072,50704384]
        App Data Sizes: [221184,376832,4714496]
        Cache Sizes: [16384,24576,16384]
        @@TVSLIM_DF
        Filesystem       1K-blocks    Used Available Use% Mounted on
        /dev/block/dm-27  51170024 5911412  45111156  12% /data/user/0
    """.trimIndent()

    @Test
    fun `la sortie de la TCL se lit en entier`() {
        val stockage = LectureStockage.interpreter(tcl)

        assertEquals(51_170_024L, stockage.totalKo)
        assertEquals(45_111_156L, stockage.libreKo)
        assertEquals(6_058_868L, stockage.utiliseKo)
        assertEquals(2_664_341_504L, stockage.applicationsOctets)
        assertEquals(1_880_899_072L, stockage.donneesOctets)
        assertEquals(961_830_912L, stockage.cacheOctets)
        assertEquals(129_970_176L, stockage.photosOctets)
        assertEquals(764_686_336L, stockage.autresOctets)
        assertTrue(stockage.renseignee)
    }

    @Test
    fun `les applications se rangent de la plus lourde a la plus legere`() {
        val applications = LectureStockage.interpreter(tcl).applications

        assertEquals(
            listOf("com.google.android.apps.mediashell", "com.google.android.katniss", "com.tcl.esticker"),
            applications.map { it.paquet },
        )
        assertEquals(114_307_072L + 376_832L + 24_576L, applications.first().totalOctets)
    }

    @Test
    fun `sans ligne Data-Free, df donne le total et le libre`() {
        val sortie = """
            App Size: 1000
            @@TVSLIM_DF
            Filesystem       1K-blocks    Used Available Use% Mounted on
            /dev/block/dm-27  51170024 5911412  45111156  12% /data
        """.trimIndent()

        val stockage = LectureStockage.interpreter(sortie)

        assertEquals(51_170_024L, stockage.totalKo)
        assertEquals(45_111_156L, stockage.libreKo)
    }

    @Test
    fun `des listes de longueurs differentes ne fabriquent aucune application`() {
        val sortie = """
            Data-Free: 10K / 20K total = 50% free
            Package Names: ["a.b","c.d"]
            App Sizes: [1]
        """.trimIndent()

        assertTrue(LectureStockage.interpreter(sortie).applications.isEmpty())
    }

    @Test
    fun `une sortie vide ne passe pas pour un stockage vide`() {
        assertFalse(LectureStockage.interpreter("").renseignee)
    }
}
