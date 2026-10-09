package net.jolabs40.tvslim.windows.update

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit

class UpdateInstallerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    private val tvSlimKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val content = ByteArray(50_000) { (it % 97).toByte() }

    private val windows = System.getProperty("os.name").startsWith("Windows")

    private fun sign(bytes: ByteArray, version: String, key: PrivateKey): String {
        val file = File(folder.root, "to-sign").apply { writeBytes(bytes) }
        val message = SignatureVerification.message(version, SignatureVerification.fingerprint(file))
        return Signature.getInstance("Ed25519").run {
            initSign(key)
            update(message)
            Base64.getEncoder().encodeToString(sign())
        }
    }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun serve(path: String, body: () -> ByteArray) = server.createContext(path) { exchange ->
            val bytes = body()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        serve("/msi") { content }
        serve("/good.sig") { sign(content, "1.2.3", tvSlimKey.private).toByteArray() }
        serve("/bad.sig") { sign(content, "1.2.3", otherKey.private).toByteArray() }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun update(signature: String) = AvailableUpdate(
        version = Version(1, 2, 3),
        notes = "",
        page = "",
        installer = PublishedFile("TVSlim-Windows-1.2.3.msi", "$base/msi"),
        signature = PublishedFile("TVSlim-Windows-1.2.3.msi.sig", "$base/$signature"),
        portable = null,
    )

    private fun installer(downloads: File) = UpdateInstaller(
        folder = downloads,
        client = GithubClient(repository = "jolabs40/TVSlim", appVersion = "test", api = base),
        publicKeyBase64 = Base64.getEncoder().encodeToString(tvSlimKey.public.encoded),
    )

    private val trickyMsi =
        File("C:\\Users\\Zoë O'Brien\\AppData\\Local\\TVSlim\\mises-a-jour\\TVSlim-Windows-1.2.3.msi")
    private val trickyExe = File("C:\\Users\\Zoë O'Brien\\AppData\\Local\\TV Slim\\TV Slim.exe")

    // --- Preparation ----------------------------------------------------------------------------

    @Test
    fun `a correctly signed installer is returned ready to install`() = runTest {
        val downloads = folder.newFolder("updates")
        var verified = false

        val msi = installer(downloads).prepare(update("good.sig"), {}, { verified = true })

        assertTrue(verified)
        assertTrue(msi.readBytes().contentEquals(content))
    }

    @Test
    fun `a badly signed installer is deleted and never returned`() = runTest {
        val downloads = folder.newFolder("updates")

        val error = runCatching {
            installer(downloads).prepare(update("bad.sig"), {}, {})
        }.exceptionOrNull()

        assertTrue(error is UpdateInstaller.InvalidSignature)
        assertEquals(emptyList<String>(), downloads.list()!!.filter { it.endsWith(".msi") })
    }

    // --- Relay ------------------------------------------------------------------------------------

    @Test
    fun `the relay waits for the app and its launcher, installs silently, then relaunches`() {
        val script = UpdateInstaller.relayScript(listOf(4242, 4243), trickyMsi, trickyExe)

        assertTrue(script.contains("Wait-Process -Id 4242,4243"))
        assertTrue(script.contains("'/passive'"))
        assertTrue("apostrophe doubled inside a literal", script.contains("Zoë O''Brien"))
        assertFalse("no double quote", script.contains('"'))
        assertEquals(2, Regex("Start-Process").findAll(script).count())
    }

    @Test
    fun `without a known executable, it installs without relaunching`() {
        val script = UpdateInstaller.relayScript(listOf(1), File("C:\\x\\a.msi"), null)

        assertEquals(1, Regex("Start-Process").findAll(script).count())
    }

    @Test
    fun `the relay is spawned through WMI, with no double quote on the command line`() {
        val script = UpdateInstaller.relayScript(listOf(4242), trickyMsi, trickyExe)
        val command = UpdateInstaller.launchCommand(script)

        assertEquals("powershell.exe", command.first())
        assertTrue(command.last().contains("Invoke-CimMethod -ClassName Win32_Process -MethodName Create"))
        assertTrue(command.none { '"' in it })
        assertTrue("apostrophes doubled twice", command.last().contains("Zoë O''''Brien"))
    }

    @Test
    fun `the app itself is among the processes to wait for`() {
        assertTrue(ProcessHandle.current().pid() in UpdateInstaller.processesToWaitFor())
    }

    @Test
    fun `the relay and its launcher are valid PowerShell scripts`() {
        assumeTrue(windows)
        val script = UpdateInstaller.relayScript(
            listOf(4242, 4243),
            File(folder.root, "Zoë O'Brien & co\\TVSlim-Windows-1.2.3.msi"),
            File(folder.root, "TV Slim\\TV Slim.exe"),
        )
        val launcher = UpdateInstaller.launchCommand(script).last()

        listOf("relay" to script, "launcher" to launcher).forEach { (name, content) ->
            val file = File(folder.root, "$name.ps1").apply { writeText(content, Charsets.UTF_8) }
            val analysis = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "\$e = \$null; [void][System.Management.Automation.Language.Parser]::ParseFile(" +
                    "'${file.absolutePath}', [ref]\$null, [ref]\$e); \$e.Count",
            ).redirectErrorStream(true).start()
            val output = analysis.inputStream.bufferedReader().readText().trim()
            analysis.waitFor()
            assertEquals("$name: no parse error expected, $output", "0", output.lines().last().trim())
        }
    }

    /**
     * Goes through both quoting layers for real: WMI starts a PowerShell that writes a file into a folder whose
     * name has a space, an apostrophe and an accent.
     */
    @Test
    fun `WMI creates the process with the command line intact`() {
        assumeTrue(windows)
        val target = File(folder.newFolder("Zoë O'Brien"), "marker.txt")
        val script = "Set-Content -Path '${target.absolutePath.replace("'", "''")}' -Value 'relay-ok'"

        // Bounded end to end: an unresponsive WMI must never hang CI.
        val journal = File(folder.root, "launch.log")
        val launch = ProcessBuilder(UpdateInstaller.launchCommand(script))
            .redirectErrorStream(true)
            .redirectOutput(journal)
            .start()
        val finished = launch.waitFor(90, TimeUnit.SECONDS)
        if (!finished) launch.destroyForcibly()
        assertTrue("WMI did not answer within 90 s: ${journal.readText()}", finished)
        assertEquals("WMI must accept the creation: ${journal.readText()}", 0, launch.exitValue())

        val limit = System.currentTimeMillis() + 60_000
        while (!target.exists() && System.currentTimeMillis() < limit) Thread.sleep(200)
        assertTrue("the relay created by WMI wrote nothing", target.exists())
        assertEquals("relay-ok", target.readText().trim())
    }
}
