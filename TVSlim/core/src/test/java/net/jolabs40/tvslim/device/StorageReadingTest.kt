package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TV storage, read from `dumpsys diskstats`, with `df` as a fallback. */
class StorageReadingTest {

    /** Output from the TCL, package lists cut to three entries. */
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
    fun `the TCL output is read in full`() {
        val storage = StorageReading.parse(tcl)

        assertEquals(51_170_024L, storage.totalKb)
        assertEquals(45_111_156L, storage.freeKb)
        assertEquals(6_058_868L, storage.usedKb)
        assertEquals(2_664_341_504L, storage.applicationsBytes)
        assertEquals(1_880_899_072L, storage.dataBytes)
        assertEquals(961_830_912L, storage.cacheBytes)
        assertEquals(129_970_176L, storage.photosBytes)
        assertEquals(764_686_336L, storage.otherBytes)
        assertTrue(storage.populated)
    }

    @Test
    fun `apps are sorted from largest to smallest`() {
        val applications = StorageReading.parse(tcl).applications

        assertEquals(
            listOf("com.google.android.apps.mediashell", "com.google.android.katniss", "com.tcl.esticker"),
            applications.map { it.packageName },
        )
        assertEquals(114_307_072L + 376_832L + 24_576L, applications.first().totalBytes)
    }

    @Test
    fun `without a Data-Free line, df gives total and free space`() {
        val output = """
            App Size: 1000
            @@TVSLIM_DF
            Filesystem       1K-blocks    Used Available Use% Mounted on
            /dev/block/dm-27  51170024 5911412  45111156  12% /data
        """.trimIndent()

        val storage = StorageReading.parse(output)

        assertEquals(51_170_024L, storage.totalKb)
        assertEquals(45_111_156L, storage.freeKb)
    }

    @Test
    fun `lists of different lengths produce no apps`() {
        val output = """
            Data-Free: 10K / 20K total = 50% free
            Package Names: ["a.b","c.d"]
            App Sizes: [1]
        """.trimIndent()

        assertTrue(StorageReading.parse(output).applications.isEmpty())
    }

    @Test
    fun `an empty output does not pass for empty storage`() {
        assertFalse(StorageReading.parse("").populated)
    }
}
