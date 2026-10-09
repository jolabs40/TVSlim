package net.jolabs40.tvslim.command

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.DirectExecutor
import net.jolabs40.tvslim.shell.Interruption
import net.jolabs40.tvslim.shell.DirectResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Free-form ADB command: what is sent from the input, sent only once, and what the journal keeps. */
class AdbConsoleTest {

    /** Fake TV that records commands and returns the given response. */
    private class FakeTv(
        private val response: (String) -> DirectResponse = { DirectResponse(0, "ok") },
    ) : DirectExecutor {
        val commands = mutableListOf<String>()

        override suspend fun executeOnce(command: String): DirectResponse {
            commands += command
            return response(command)
        }
    }

    private fun journal() = JournalRepository(File.createTempFile("journal", ".json").also { it.delete() })

    private fun ready(command: String) = CommandInput.Ready(command)

    private fun rejected(rejection: CommandRejection) = CommandInput.Rejected(rejection)

    @Test
    fun `a bare command is sent as is`() {
        assertEquals(ready("pm list packages -d"), AdbConsole.read("  pm list packages -d "))
        assertEquals(ready("adbd --version"), AdbConsole.read("adbd --version"))
    }

    @Test
    fun `the adb shell wrapper is stripped, device and quotes included`() {
        assertEquals(ready("pm list packages -d"), AdbConsole.read("adb shell pm list packages -d"))
        assertEquals(
            ready("getprop ro.product.model"),
            AdbConsole.read("adb -s 192.168.2.135:5555 shell getprop ro.product.model"),
        )
        assertEquals(
            ready("dumpsys package net.jolabs40.tvslim | grep version"),
            AdbConsole.read("adb -d shell \"dumpsys package net.jolabs40.tvslim | grep version\""),
        )
        // Quotes around only part of the command stay, for the TV shell to handle.
        assertEquals(
            ready("settings put global name 'a b'"),
            AdbConsole.read("adb shell settings put global name 'a b'"),
        )
    }

    @Test
    fun `other adb commands and empty input are not sent`() {
        assertEquals(rejected(CommandRejection.NOT_SHELL), AdbConsole.read("adb install HippieTV.apk"))
        assertEquals(rejected(CommandRejection.NOT_SHELL), AdbConsole.read("adb reboot"))
        assertEquals(rejected(CommandRejection.EMPTY), AdbConsole.read("adb shell"))
        assertEquals(rejected(CommandRejection.EMPTY), AdbConsole.read("   "))
        assertEquals(
            rejected(CommandRejection.TOO_LONG),
            AdbConsole.read("echo " + "x".repeat(AdbConsole.MAX_LENGTH)),
        )
    }

    @Test
    fun `a command is sent once and journaled with no undo`() = runTest {
        val tv = FakeTv { DirectResponse(0, "package:com.tcl.gallery") }
        val logbook = journal()

        val exchange = AdbConsole(tv) { logbook }.send("pm list packages -d")

        assertEquals(listOf("pm list packages -d"), tv.commands)
        assertTrue(exchange.succeeded)
        assertEquals("package:com.tcl.gallery", exchange.output)
        with(logbook.actions.value.single()) {
            assertEquals(ActionType.COMMAND, type)
            assertEquals("pm list packages -d", target)
            assertEquals("", undoCommand)
            assertTrue(succeeded)
        }
    }

    @Test
    fun `a command cut off keeps the output written so far and is not replayed`() = runTest {
        val tv = FakeTv { DirectResponse(null, "partial\n", Interruption.TIMEOUT, "timed out") }
        val logbook = journal()

        val exchange = AdbConsole(tv) { logbook }.send("logcat")

        assertEquals(1, tv.commands.size)
        assertFalse(exchange.succeeded)
        assertEquals("partial\n", exchange.output)
        with(logbook.actions.value.single()) {
            assertFalse(succeeded)
            assertEquals("Coupée par le délai maximal.", message)
        }
    }

    @Test
    fun `a nonzero exit code is a failure, and the output gives the reason`() = runTest {
        val tv = FakeTv { DirectResponse(255, "Error: unknown command 'lister'") }
        val logbook = journal()

        val exchange = AdbConsole(tv) { logbook }.send("pm lister")

        assertFalse(exchange.succeeded)
        assertEquals("Error: unknown command 'lister'", logbook.actions.value.single().message)
    }

    @Test
    fun `oversized output is truncated for display`() = runTest {
        val tv = FakeTv { DirectResponse(0, "x".repeat(AdbConsole.MAX_OUTPUT + 10)) }

        val exchange = AdbConsole(tv) { null }.send("dumpsys")

        assertEquals(AdbConsole.MAX_OUTPUT, exchange.output.length)
        assertEquals(AdbConsole.MAX_OUTPUT + 10, exchange.receivedLength)
        assertTrue(exchange.truncated)
    }
}
