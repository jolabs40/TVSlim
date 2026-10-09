package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-package hints from ADB. The output follows the format read on the TCL (Android 14): factory and
 * updated paths, one uid per user, `--brief` intent queries. Trimmed to a few packages.
 */
class PackageHintsTest {

    private val tclOutput = """
        @@TVSLIM_FILES
        package:/product/overlay/MtkMdnsOffloadServiceOverlay.apk=com.mediatek.android.tv.mdns.offload.overlay uid:10118,1010118
        package:/apex/com.android.tethering/priv-app/ServiceConnectivityResources@UTT2.250416.001/ServiceConnectivityResources.apk=com.android.connectivity.resources uid:10102
        package:/data/app/~~BFrwO2y2GoKrjngWosmqgQ==/flar2.homebutton-zbojoxdPjFpGbLxfTzjbVA==/base.apk=flar2.homebutton uid:10125
        package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard uid:1000
        package:/data/app/~~Z7uCSaOtVZnzrLNOW89RgA==/com.google.android.katniss-mO9HKX1UmTQ6u2nN54tiEQ==/base.apk=com.google.android.katniss uid:10036
        package:/system/app/SecureElement/SecureElement.apk=com.android.se uid:1068
        package:/system_ext/priv-app/TclTvInput/TclTvInput.apk=com.tcl.tvinput uid:1000
        @@TVSLIM_FACTORY
        package:/product/overlay/MtkMdnsOffloadServiceOverlay.apk=com.mediatek.android.tv.mdns.offload.overlay
        package:/apex/com.android.tethering/priv-app/ServiceConnectivityResources@UTT2.250416.001/ServiceConnectivityResources.apk=com.android.connectivity.resources
        package:/system_ext/app/TGuard/TGuard.apk=com.tcl.guard
        package:/product/priv-app/Katniss/Katniss.apk=com.google.android.katniss
        package:/system/app/SecureElement/SecureElement.apk=com.android.se
        package:/system_ext/priv-app/TclTvInput/TclTvInput.apk=com.tcl.tvinput
        @@TVSLIM_TV_INPUT
        2 services found:
          Service #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            com.tcl.tvinput/.TvPassThroughService
          Service #1:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            com.tcl.tvinput/.TunerInputService
        @@TVSLIM_ACCESSIBILITY
        No services found
        @@TVSLIM_KEYBOARD
        1 services found:
          Service #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
            flar2.homebutton/.utils.BMIME
        @@TVSLIM_BOOT
        1 receivers found:
          Receiver #0:
            priority=1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.tcl.guard/.receiver.BootReceiver
        No receivers found
        @@TVSLIM_ICONS
        1 activities found:
          Activity #0:
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            flar2.homebutton/.MainActivity
    """.trimIndent()

    @Test
    fun `each package carries its location, its identity and what it declares`() {
        val hints = PackageHintsReading.parse(tclOutput)

        val guard = hints.getValue("com.tcl.guard")
        assertEquals("system_ext/app", guard.location)
        assertTrue(guard.hasSystemPrivileges)
        assertFalse(guard.updated)
        assertFalse(guard.icon)
        assertEquals(setOf(SensitiveDeclaration.BOOT), guard.declarations)

        // Updated: the location is the factory one, not /data/app.
        val katniss = hints.getValue("com.google.android.katniss")
        assertEquals("/product/priv-app/Katniss/Katniss.apk", katniss.path)
        assertEquals("product/priv-app", katniss.location)
        assertTrue(katniss.updated)
        assertTrue(katniss.privileged)
        assertEquals(10036, katniss.uid)
        assertFalse(katniss.reservedUid)

        val tuner = hints.getValue("com.tcl.tvinput")
        assertEquals(setOf(SensitiveDeclaration.TV_INPUT), tuner.declarations)
        assertTrue(tuner.privileged)

        val secureElement = hints.getValue("com.android.se")
        assertEquals(1068, secureElement.uid)
        assertTrue(secureElement.reservedUid)
        assertFalse(secureElement.hasSystemPrivileges)

        // One uid per user: the first one belongs to the main user.
        val overlay = hints.getValue("com.mediatek.android.tv.mdns.offload.overlay")
        assertEquals(10118, overlay.uid)
        assertEquals("product/overlay", overlay.location)
        assertEquals("apex/com.android.tethering/priv-app", hints.getValue("com.android.connectivity.resources").location)

        // User-installed, no factory version: the path stays in /data/app.
        val button = hints.getValue("flar2.homebutton")
        assertEquals("data/app", button.location)
        assertFalse(button.updated)
        assertTrue(button.icon)
        assertEquals(setOf(SensitiveDeclaration.KEYBOARD), button.declarations)
    }

    @Test
    fun `without a marker, nothing is made up`() {
        assertTrue(PackageHintsReading.parse("").isEmpty())
        assertTrue(PackageHintsReading.parse("/system/bin/sh: cmd: not found").isEmpty())
    }

    @Test
    fun `the command announces each section without starting a shell comment`() {
        val command = PackageHintsReading.COMMAND
        assertTrue(command, command.split(' ', ';').map { it.trim() }.none { it.startsWith("#") })

        val markers = listOf(PackageHintsReading.FILES_MARKER, PackageHintsReading.FACTORY_MARKER, PackageHintsReading.ICONS_MARKER) +
            SensitiveDeclaration.entries.map(PackageHintsReading::marker)
        assertEquals("Two sections with the same name would merge", markers.size, markers.toSet().size)
        markers.forEach { assertTrue("The command must announce $it", command.contains("echo $it;") || command.endsWith("echo $it")) }
        assertFalse("Read only", command.contains(" disable") || command.contains("uninstall"))
    }

    @Test
    fun `a failing last query does not discard the sections already read`() = runTest {
        // A compound command exits with the code of its last query.
        val reader = RemoteReader(
            object : CommandExecutor {
                override suspend fun execute(command: String) = ShellResult(code = 255, output = tclOutput)
            },
        )

        assertEquals(1000, reader.hints().getValue("com.tcl.guard").uid)
    }
}
