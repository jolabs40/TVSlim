package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.DirectExecutor
import net.jolabs40.tvslim.shell.DirectResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

/** Reboot: a single command, never retried, and one journal entry. */
class RebootTest {

    private class FakeTv(private val failing: Boolean = false) : DirectExecutor {
        val commands = mutableListOf<String>()

        override suspend fun executeOnce(command: String): DirectResponse {
            commands += command
            if (failing) throw IOException("Connection reset")
            return DirectResponse(code = null, output = "")
        }
    }

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    @Test
    fun `a normal reboot runs once and is logged without an undo`() = runTest {
        val tv = FakeTv()
        val journal = journal()

        Reboot(tv, journal).reboot()

        assertEquals(listOf("reboot"), tv.commands)
        val line = journal.actions.value.single()
        assertEquals(ActionType.COMMAND, line.type)
        assertEquals("reboot", line.target)
        assertEquals("", line.undoCommand)
    }

    @Test
    fun `the connection dropping mid-command is the expected outcome`() = runTest {
        val tv = FakeTv(failing = true)
        val journal = journal()

        Reboot(tv, journal).reboot()

        assertEquals(1, tv.commands.size)
        assertEquals(1, journal.actions.value.size)
    }
}
