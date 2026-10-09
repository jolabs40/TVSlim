package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory reading, on output taken from the Shield: thousands separators, `K` suffixes, and system
 * processes with dotless names, which must not get a Stop button.
 */
class MemoryReaderTest {

    private class FixedExecutor(private val output: String, private val code: Int = 0) :
        CommandExecutor {
        override suspend fun execute(command: String) = ShellResult(code, output)
    }

    private val shieldOutput = """
        Total PSS by process:
            183,873K: system (pid 3703 state 0 oom -900)
            161,015K: com.spocky.projengmenu (pid 4799 state 14 oom 150 / activities)
            137,665K: vendor.nvidia.hardware.graphics.composer@2.0-service (pid 3387)
            128,382K: com.google.android.tts (pid 6970 state 4 oom 200)
             84,036K: surfaceflinger (pid 3407)
             38,580K: com.android.vending:background (pid 6822 state 19 oom 955)

        Total PSS by OOM adjustment:
            183,873K: Native
        Total RAM: 3,016,708K (status normal)
         Free RAM: 1,097,170K (  431,230K cached pss +   552,380K cached kernel +   113,560K free)
         Used RAM: 1,769,972K (1,540,296K used pss +   229,676K kernel)
         Lost RAM:   208,199K
             ZRAM:    18,728K physical used for    80,232K in swap (  524,284K total swap)
    """.trimIndent()

    @Test
    fun `totals are read despite thousands separators`() = runTest {
        val memory = RemoteReader(FixedExecutor(shieldOutput)).memory()

        assertTrue(memory.populated)
        assertEquals(3_016_708L, memory.totalKb)
        assertEquals(1_097_170L, memory.freeKb)
        assertEquals(1_769_972L, memory.usedKb)
        assertEquals(431_230L, memory.cacheKb)
        assertEquals(18_728L, memory.zramKb)
    }

    @Test
    fun `processes are sorted from heaviest to lightest`() = runTest {
        val memory = RemoteReader(FixedExecutor(shieldOutput)).memory()

        assertEquals(6, memory.processes.size)
        assertEquals("system", memory.processes.first().name)
        assertEquals(179, memory.processes.first().megabytes)
        assertEquals(3703, memory.processes.first().pid)

        val weight = memory.processes.map { it.kilobytes }
        assertEquals(weight.sortedDescending(), weight)
    }

    @Test
    fun `the OOM section is not mistaken for the process list`() = runTest {
        val memory = RemoteReader(FixedExecutor(shieldOutput)).memory()

        // `183,873K: Native` belongs to the second table; counting it would duplicate an entry.
        assertEquals(1, memory.processes.count { it.kilobytes == 183_873L })
        assertTrue(memory.processes.none { it.name == "Native" })
    }

    @Test
    fun `only app processes can be stopped`() = runTest {
        val memory = RemoteReader(FixedExecutor(shieldOutput)).memory()
        fun processes(name: String) = memory.processes.first { it.name == name }

        assertTrue(processes("com.spocky.projengmenu").isApp)
        assertFalse("system n'est pas une application", processes("system").isApp)
        assertFalse("surfaceflinger non plus", processes("surfaceflinger").isApp)
        assertFalse(
            "un service du fabricant non plus",
            processes("vendor.nvidia.hardware.graphics.composer@2.0-service").isApp,
        )
    }

    @Test
    fun `a secondary process maps to the package that owns it`() = runTest {
        val memory = RemoteReader(FixedExecutor(shieldOutput)).memory()
        val background = memory.processes.first { it.name == "com.android.vending:background" }

        // Force-stop targets `com.android.vending`, not `com.android.vending:background`.
        assertEquals("com.android.vending", background.packageName)
        assertTrue(background.isApp)
    }

    @Test
    fun `a failed read returns no made-up figures`() = runTest {
        val memory = RemoteReader(FixedExecutor("", code = 1)).memory()

        assertFalse(memory.populated)
        assertTrue(memory.processes.isEmpty())
    }
}
