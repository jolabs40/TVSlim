package net.jolabs40.tvslim.applications

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.journal.JournalRepository
import net.jolabs40.tvslim.journal.ActionType
import net.jolabs40.tvslim.shell.FileUploader
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.Base64

/**
 * The Applications tab against a fake device. The real helper is covered on hardware by `ApplicationsMaterielTest`
 * (TCL, 46 apps; Pixel, 227).
 */
class DeviceApplicationsTest {

    private val icon = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val iconBase64 = Base64.getEncoder().encodeToString(icon)

    private val list = """
        TVSLIM_AIDE 1
        A	com.google.android.youtube.tv	1234	1	1	com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
        A	com.spocky.projengmenu	95	0	1	com.spocky.projengmenu/com.spocky.projengmenu.ui.home.MainActivity
        A	net.jolabs40.tvslim	1	0	0	-
        WARNING: linker: something noisy
    """.trimIndent()

    /** Answers the helper's commands and records every command it receives. */
    private inner class FakeDevice(
        val listResponse: String = list,
        val thirdParty: Set<String> = setOf("com.spocky.projengmenu", "net.jolabs40.tvslim"),
    ) : CommandExecutor, FileUploader {
        val commands = mutableListOf<String>()
        var sent: ByteArray? = null

        override suspend fun execute(command: String): ShellResult {
            commands += command
            return when {
                command.endsWith(" liste") -> ShellResult(0, listResponse)
                command.contains(" details ") -> ShellResult(
                    0,
                    "TVSLIM_AIDE 1\n" + command.substringAfter(" details ").split(' ').drop(1).joinToString("\n") { packageName ->
                        if (packageName == "net.jolabs40.tvslim") "E\t$packageName\tNameNotFoundException" else "D\t$packageName\tNom de $packageName\t$iconBase64"
                    },
                )
                command.startsWith("pm list packages -3 ") ->
                    ShellResult(0, thirdParty.filter { it.contains(command.substringAfterLast(' ')) }.joinToString("\n") { "package:$it" })
                command.startsWith("pm uninstall ") -> ShellResult(0, "Success")
                command.startsWith("am start ") -> ShellResult(0, "Starting: Intent { cmp=… }")
                else -> ShellResult(0, "")
            }
        }

        override suspend fun send(
            source: InputStream,
            size: Long,
            path: String,
            date: Long,
            cancelled: () -> Boolean,
            onSent: (sent: Long) -> Unit,
        ): ShellResult {
            sent = source.use { it.readBytes() }
            return ShellResult(0, "")
        }
    }

    private class MemoryCacheApplications : ApplicationsCache {
        val content = mutableMapOf<String, ApplicationDetails>()
        override fun read(packageName: String, versionCode: Long) = content["$packageName@$versionCode"]
        override fun write(packageName: String, versionCode: Long, details: ApplicationDetails) {
            content["$packageName@$versionCode"] = details
        }
    }

    private fun helper(): InputStream = ByteArrayInputStream(byteArrayOf(1, 2, 3))

    @Test
    fun `reads the list then the details, and deletes the helper at the end`() = runTest {
        val device = FakeDevice()
        val steps = mutableListOf<Pair<Int, Int>>()

        val result = ApplicationsReader(device, device, ::helper, MemoryCacheApplications())
            .read { _, done, total -> steps += done to total } as ReadResult.Read

        assertTrue(device.sent!!.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(listOf(0 to 3, 3 to 3), steps)
        val youtube = result.applications.single { it.packageName == "com.google.android.youtube.tv" }
        assertEquals("Nom de com.google.android.youtube.tv", youtube.name)
        assertTrue(youtube.icon!!.contentEquals(icon))
        assertTrue(youtube.system)
        // A package the helper failed to read keeps its package name and has no icon.
        val tvslim = result.applications.single { it.packageName == "net.jolabs40.tvslim" }
        assertEquals("net.jolabs40.tvslim", tvslim.name)
        assertNull(tvslim.icon)
        assertNull(tvslim.launch)
        assertFalse(tvslim.active)
        assertEquals("rm -f ${ApplicationsReader.HELPER_PATH}", device.commands.last())
    }

    @Test
    fun `packages already in the cache are not read again`() = runTest {
        val cache = MemoryCacheApplications()
        cache.write("com.google.android.youtube.tv", 1234, ApplicationDetails("YouTube", icon))
        cache.write("com.spocky.projengmenu", 95, ApplicationDetails("Projectivy", icon))
        val device = FakeDevice()

        val result = ApplicationsReader(device, device, ::helper, cache).read() as ReadResult.Read

        val details = device.commands.single { it.contains(" details ") }
        assertTrue(details, details.endsWith(" details ${ApplicationsReader.ICON_SIZE} net.jolabs40.tvslim"))
        assertEquals(listOf("net.jolabs40.tvslim", "Projectivy", "YouTube"), result.applications.map { it.name })
    }

    @Test
    fun `a helper reporting another version is rejected`() = runTest {
        val device = FakeDevice(listResponse = "TVSLIM_AIDE 2\nA\tx\t1\t0\t1\t-")

        val result = ApplicationsReader(device, device, ::helper, MemoryCacheApplications()).read()

        assertEquals(ReadCause.HELPER_REJECTED, (result as ReadResult.Failure).cause)
        assertEquals("rm -f ${ApplicationsReader.HELPER_PATH}", device.commands.last())
    }

    @Test
    fun `without the helper in the resources, nothing is sent`() = runTest {
        val device = FakeDevice()

        val result = ApplicationsReader(device, device, { null }, MemoryCacheApplications()).read()

        assertEquals(ReadCause.HELPER_MISSING, (result as ReadResult.Failure).cause)
        assertTrue(device.commands.isEmpty())
    }

    @Test
    fun `only a user-installed app can be uninstalled, and the journal records it`() = runTest {
        val device = FakeDevice()
        val journal = JournalRepository(Files.createTempFile("journal", ".json").toFile().apply { delete() })
        val actions = ApplicationsActions(device) { journal }
        val reading = ApplicationsReader(device, device, ::helper, MemoryCacheApplications()).read() as ReadResult.Read
        val projectivy = reading.applications.single { it.packageName == "com.spocky.projengmenu" }
        val youtube = reading.applications.single { it.packageName == "com.google.android.youtube.tv" }

        assertFalse(actions.uninstall(youtube).succeeded)
        assertFalse(device.commands.any { it == "pm uninstall com.google.android.youtube.tv" })

        assertTrue(actions.uninstall(projectivy).succeeded)
        val logged = journal.actions.value.single()
        assertEquals(ActionType.UNINSTALLATION, logged.type)
        assertEquals("", logged.undoCommand)
    }

    @Test
    fun `open starts the launcher activity, single-quoted`() = runTest {
        val device = FakeDevice()
        val actions = ApplicationsActions(device) { null }
        val youtube = ApplicationsReader.readList(list).single { it.packageName == "com.google.android.youtube.tv" }

        assertTrue(actions.open(youtube).succeeded)
        assertEquals("am start -n '${youtube.launch}'", device.commands.last())
        assertFalse(actions.open(ApplicationsReader.readList(list).single { it.launch == null }).succeeded)
    }

    @Test
    fun `the disk cache reads back names and icons`() {
        val folder = Files.createTempDirectory("cache-applications").toFile()
        try {
            FileCacheApplications(folder).write("com.a", 3, ApplicationDetails("A", icon))

            val reread = FileCacheApplications(folder).read("com.a", 3)

            assertEquals("A", reread!!.name)
            assertTrue(reread.icon.contentEquals(icon))
            assertNull(FileCacheApplications(folder).read("com.a", 4))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the bundled helper is in the core assets`() {
        val helper = File("src/main/assets/${ApplicationsReader.RESOURCE_PATH}")
        assertTrue("${helper.absolutePath} : lancer ./gradlew :aide:copierDansLeNoyau", helper.isFile)
        // An APK is a zip archive holding classes.dex.
        val bytes = helper.readBytes()
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("classes.dex"))
    }
}
