package net.jolabs40.tvslim.windows.mcp

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.jolabs40.tvslim.catalog.CatalogRepository
import net.jolabs40.tvslim.device.RemoteReader
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.journal.JournalAction
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import net.jolabs40.tvslim.windows.adb.ConnectionProblem
import net.jolabs40.tvslim.windows.data.WindowsPreferences
import net.jolabs40.tvslim.windows.network.DiscoveredDevice
import net.jolabs40.tvslim.windows.network.DiscoveryResult
import net.jolabs40.tvslim.windows.network.TvAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TvSlimToolsTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A TCL as RemoteReader sees it: the gallery disabled, the recommendations active again. */
    private val snapshot = """
        @@TVSLIM_D
        package:com.tcl.gallery
        @@TVSLIM_E
        package:com.tcl.waterfall.overseas
        package:com.tcl.tv
        package:com.example.mystery
        package:com.spocky.projengmenu
        @@TVSLIM_P
        TCL
        65C89K
        14
        tcl9618-user
        @@TVSLIM_B
        TCL
        @@TVSLIM_M
        MemTotal:        2513404 kB
        MemAvailable:     628112 kB
        @@TVSLIM_H
        priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false
        com.spocky.projengmenu/.ui.home.MainActivity
        @@TVSLIM_T
        package:com.spocky.projengmenu
    """.trimIndent()

    private class FakeLink(private val output: String, private val refusal: ConnectionProblem? = null) : TvLink {
        val commands = mutableListOf<String>()
        var connections = 0
        private var connected: TvAddress? = null

        override val executor = object : CommandExecutor {
            override suspend fun execute(command: String): ShellResult {
                commands += command
                return if (command == RemoteReader.COMMAND) ShellResult(0, output) else ShellResult(0, "")
            }
        }

        override fun isConnectedTo(address: TvAddress) = connected == address

        override suspend fun connect(address: TvAddress): ConnectionProblem? {
            connections++
            if (refusal == null) connected = address
            return refusal
        }

        override fun disconnect() {
            connected = null
        }
    }

    private fun tools(link: TvLink, journals: File = folder.newFolder("journaux")) = TvSlimTools(
        link = link,
        catalogs = CatalogRepository(language = { "en" }),
        preferences = WindowsPreferences(File(folder.root, "preferences.json")),
        discoveries = { flowOf(DiscoveryResult(listOf(DiscoveredDevice("adb", "192.168.2.193", 5555, "SHIELD")), firstRoundDone = true)) },
        journals = journals,
    )

    private suspend fun TvSlimTools.call(name: String, vararg arguments: Pair<String, String>): String =
        all.first { it.name == name }.call(buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } })

    private suspend fun journalWithWaterfallDisabled(): File {
        val journals = folder.newFolder("journaux")
        JournalRepository(File(journals, "192_168_2_135.json")).add(
            listOf(
                JournalAction(1_760_000_000_000, ActionType.DISABLING, "com.tcl.waterfall.overseas", "TCL recommendations", "pm enable com.tcl.waterfall.overseas", true),
                JournalAction(1_760_000_100_000, ActionType.DISABLING, "com.tcl.gallery", "TCL Gallery", "pm enable com.tcl.gallery", true),
            ),
        )
        return journals
    }

    @Test
    fun `the overview reads the TV, the catalogue and the drift`() = runTest {
        val link = FakeLink(snapshot)
        val text = tools(link, journalWithWaterfallDisabled()).call("get_tv_overview", "address" to "192.168.2.135")

        assertTrue(text, text.startsWith("TCL 65C89K at 192.168.2.135:5555"))
        assertTrue(text, "Home screen: Projectivy Launcher" in text)
        assertTrue(text, "Protected packages on this TV, never disabled by TV Slim: 1" in text)
        assertTrue(text, "Not in the catalogue: 1 " in text)
        assertTrue(text, "Drift: 1 package(s) TV Slim disabled are active again" in text)
    }

    @Test
    fun `drift names the package that came back`() = runTest {
        val text = tools(FakeLink(snapshot), journalWithWaterfallDisabled()).call("check_drift", "address" to "192.168.2.135")

        assertTrue(text, "`com.tcl.waterfall.overseas`, TCL recommendations: active" in text)
        assertFalse(text, "com.tcl.gallery" in text)
    }

    @Test
    fun `reading sends nothing but reads`() = runTest {
        val link = FakeLink(snapshot)
        val tools = tools(link, journalWithWaterfallDisabled())

        listOf("get_tv_overview", "list_tv_packages", "check_drift", "get_unknown_packages_report").forEach {
            tools.call(it, "address" to "192.168.2.135")
        }

        val writes = Regex("""\b(pm (disable|enable|uninstall|install|grant|revoke)|cmd package (install|uninstall)|settings put|appops set|rm |push|am (start|broadcast)|reboot)\b""")
        link.commands.forEach { command -> assertFalse(command, writes.containsMatchIn(command)) }
        assertEquals("Several calls to the same TV share one connection", 1, link.connections)
    }

    @Test
    fun `an address from outside the local network is refused before connecting`() = runTest {
        val link = FakeLink(snapshot)

        val failure = runCatching { tools(link).call("get_tv_overview", "address" to "8.8.8.8") }.exceptionOrNull()

        assertTrue(failure is ToolFailure)
        assertEquals(0, link.connections)
    }

    @Test
    fun `a TV that has not authorized this PC says what to do`() = runTest {
        val link = FakeLink(snapshot, refusal = ConnectionProblem.UNAUTHORIZED)

        val failure = runCatching { tools(link).call("get_tv_overview", "address" to "192.168.2.135") }.exceptionOrNull()

        assertTrue(failure is ToolFailure)
        assertTrue(failure!!.message!!, "Allow USB debugging?" in failure.message!!)
    }

    @Test
    fun `the journal and the catalogue are read without the TV`() = runTest {
        val link = FakeLink(snapshot)
        val tools = tools(link, journalWithWaterfallDisabled())

        val journal = tools.call("get_journal", "address" to "192.168.2.135")
        val known = tools.call("describe_package", "package" to "com.tcl.gallery")
        val protected = tools.call("describe_package", "package" to "com.tcl.tv")
        val unknown = tools.call("describe_package", "package" to "com.example.mystery")

        assertTrue(journal, "2 action(s) recorded for 192.168.2.135" in journal)
        assertTrue(known, "TCL Gallery" in known)
        assertTrue(protected, "is protected" in protected)
        assertTrue(unknown, "is not in TV Slim's catalogue" in unknown)
        assertEquals(0, link.connections)
    }

    @Test
    fun `without an address, the TV last joined from this PC is used`() = runTest {
        WindowsPreferences(File(folder.root, "preferences.json")).rememberAddress("192.168.2.135", 5555)
        val link = FakeLink(snapshot)

        tools(link).all.first { it.name == "get_tv_overview" }.call(JsonObject(emptyMap()))

        assertTrue(link.isConnectedTo(TvAddress("192.168.2.135", 5555)))
    }

    @Test
    fun `found TVs come with the name TV Slim knows them by`() = runTest {
        val text = tools(FakeLink(snapshot)).call("find_tvs")

        assertTrue(text, "- 192.168.2.193:5555, SHIELD" in text)
    }
}
