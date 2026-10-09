package net.jolabs40.tvslim.device

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.shell.CommandExecutor
import net.jolabs40.tvslim.shell.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Remote state reading.
 *
 * The first test comes from a real failure on the TCL: markers started with `#`, which the shell reads as
 * a comment. The rest of the compound command was swallowed, `pm` never ran, and the companion showed an
 * empty TV with no error.
 */
class RemoteReaderTest {

    private class FixedExecutor(private val output: String, private val code: Int = 0) :
        CommandExecutor {
        var incoming: String? = null
        override suspend fun execute(command: String): ShellResult {
            incoming = command
            return ShellResult(code, output)
        }
    }

    @Test
    fun `the command contains no word that starts a shell comment`() {
        val commentWords = RemoteReader.COMMAND
            .split(' ', ';')
            .map { it.trim() }
            .filter { it.startsWith("#") }

        assertTrue(
            "These words would silence the rest of the line: $commentWords",
            commentWords.isEmpty(),
        )
    }

    @Test
    fun `each section is announced by its marker`() {
        val markers = listOf("_D", "_E", "_P", "_B", "_M", "_H", "_L", "_U", "_T")
            .map { RemoteReader.MARKER_PREFIX + it.removePrefix("_") }
        markers.forEach { marker ->
            assertTrue(
                "The command must announce $marker",
                RemoteReader.COMMAND.contains("echo $marker"),
            )
        }
    }

    @Test
    fun `a realistic output is split correctly`() = runTest {
        val output = """
            @@TVSLIM_D
            package:com.tcl.gallery
            package:com.netflix.ninja
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:net.jolabs40.tvslim
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
            @@TVSLIM_L
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.spocky.projengmenu/.ui.home.MainActivity
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.apps.tv.launcherx/.home.HomeActivity
            priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.android.tv.settings/.system.FallbackHome
        """.trimIndent()

        val photo = RemoteReader(FixedExecutor(output)).snapshot(
            watchedPackages = listOf("com.tcl.gallery", "com.spocky.projengmenu", "absent.here"),
            homePackages = setOf("com.google.android.apps.tv.launcherx"),
        )

        assertEquals(PackageState.DISABLED, photo.states["com.tcl.gallery"])
        assertEquals(PackageState.ACTIVE, photo.states["com.spocky.projengmenu"])
        assertEquals(PackageState.ABSENT, photo.states["absent.here"])

        assertEquals("TCL", photo.info.brand)
        assertEquals("TCL", photo.info.retailBrand)
        assertEquals("65C89K", photo.info.model)
        assertEquals("14", photo.info.androidVersion)
        assertEquals(2, photo.info.disabledPackages)
        assertEquals(2, photo.info.installedPackages)
        assertEquals(2454, photo.info.totalMemoryMb)
        assertEquals("com.spocky.projengmenu", photo.info.currentHome)

        // The factory home does not count as a replacement launcher.
        assertEquals(listOf("com.spocky.projengmenu"), photo.info.thirdPartyLaunchers.map { it.packageName })
    }

    @Test
    fun `an empty retail brand does not shift any property`() = runTest {
        val output = """
            @@TVSLIM_P
            NVIDIA
            SHIELD Android TV
            11
            RQ1A.210105.003
            @@TVSLIM_B
            @@TVSLIM_M
            MemTotal:        3016092 kB
        """.trimIndent()

        val info = RemoteReader(FixedExecutor(output)).snapshot(emptyList(), emptySet()).info

        assertEquals("", info.retailBrand)
        assertEquals("NVIDIA", info.brand)
        assertEquals("RQ1A.210105.003", info.build)
    }

    @Test
    fun `a fallback screen is never taken for a replacement launcher`() = runTest {
        // Seen on a Shield: FallbackHome answers category.HOME with a negative priority. Counting it as a
        // replacement would let the engine disable the factory home, and the device would boot to a blank screen.
        val output = """
            @@TVSLIM_L
            2 activities found:
              Activity #0:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.tvlauncher/.MainActivity
              Activity #1:
                priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.tv.settings/.system.FallbackHome
        """.trimIndent()

        val photo = RemoteReader(FixedExecutor(output)).snapshot(
            watchedPackages = emptyList(),
            homePackages = setOf("com.google.android.tvlauncher"),
        )

        assertTrue(
            "No third-party launcher here: ${photo.info.thirdPartyLaunchers.map { it.packageName }}",
            photo.info.thirdPartyLaunchers.isEmpty(),
        )
    }

    @Test
    fun `a disabled factory home is found, without setup wizards or fallback screens`() = runTest {
        // From the TCL: disabled Google TV only shows up with --query-flags 512, next to provisioning,
        // a setup wizard and two fallback screens.
        val output = """
            @@TVSLIM_D
            package:com.google.android.apps.tv.launcherx
            package:com.google.android.tungsten.setupwraith
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:net.jolabs40.startlight.debug
            @@TVSLIM_H
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity
            @@TVSLIM_L
            com.spocky.projengmenu/.ui.home.MainActivity
            net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity
            com.android.tv.settings/.system.FallbackHome
            @@TVSLIM_U
            10 activities found:
              Activity #0:
                priority=10 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.managedprovisioning/.preprovisioning.PostEncryptionActivity
              Activity #1:
                priority=4 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.tungsten.setupwraith/.MainActivity
              Activity #2:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.HomeActivity
              Activity #3:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.VanillaModeHomeActivity
              Activity #4:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.spocky.projengmenu/.ui.home.MainActivity
              Activity #5:
                priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity
              Activity #6:
                priority=-100 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false
                android/com.android.internal.app.SystemUserHomeActivity
              Activity #7:
                priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.tv.settings/.system.FallbackHome
            @@TVSLIM_T
            package:flar2.homebutton
            package:com.spocky.projengmenu
            package:net.jolabs40.startlight.debug
        """.trimIndent()

        val info = RemoteReader(FixedExecutor(output)).snapshot(
            watchedPackages = emptyList(),
            homePackages = setOf(
                "com.google.android.tungsten.setupwraith",
                "com.google.android.apps.tv.launcherx",
                "com.google.android.tvlauncher",
            ),
        ).info

        assertEquals(
            listOf(
                FactoryHome(
                    packageName = "com.google.android.apps.tv.launcherx",
                    component = "com.google.android.apps.tv.launcherx/.home.HomeActivity",
                    active = false,
                ),
            ),
            info.factoryHomes,
        )
        assertEquals("net.jolabs40.startlight.debug/net.jolabs40.startlight.HomeActivity", info.homeComponent)
        // The safeguard is unchanged: third-party launchers still come from the unflagged query.
        assertEquals(
            listOf("com.spocky.projengmenu", "net.jolabs40.startlight.debug"),
            info.thirdPartyLaunchers.map { it.packageName },
        )
    }

    @Test
    fun `system packages are everything the user did not install`() = runTest {
        val output = """
            @@TVSLIM_D
            package:com.google.android.apps.tv.launcherx
            package:com.tcl.tv.tclhome_passive
            @@TVSLIM_E
            package:com.spocky.projengmenu
            package:com.mediatek.wwtv.tvcenter
            @@TVSLIM_T
            package:com.spocky.projengmenu
        """.trimIndent()

        val photo = RemoteReader(FixedExecutor(output)).snapshot(emptyList(), emptySet())

        assertEquals(
            mapOf(
                "com.google.android.apps.tv.launcherx" to PackageState.DISABLED,
                "com.tcl.tv.tclhome_passive" to PackageState.DISABLED,
                "com.mediatek.wwtv.tvcenter" to PackageState.ACTIVE,
            ),
            photo.systemPackages,
        )

        // Without the third-party list, nothing: Projectivy would pass for a system package.
        val withoutThirdParty = RemoteReader(FixedExecutor(output.substringBefore("@@TVSLIM_T")))
            .snapshot(emptyList(), emptySet())
        assertTrue(withoutThirdParty.systemPackages.isEmpty())
    }

    @Test
    fun `without the third-party app list, only catalogue homes count as factory homes`() = runTest {
        val output = """
            @@TVSLIM_D
            package:com.google.android.tvlauncher
            @@TVSLIM_U
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.tvlauncher/.MainActivity
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.spocky.projengmenu/.ui.home.MainActivity
        """.trimIndent()

        val info = RemoteReader(FixedExecutor(output))
            .snapshot(emptyList(), setOf("com.google.android.tvlauncher"))
            .info

        assertEquals(listOf("com.google.android.tvlauncher"), info.factoryHomes.map { it.packageName })
        assertFalse(info.factoryHomes.single().active)
    }

    @Test
    fun `an Android that ignores the flag yields no home from its help message`() = runTest {
        val output = """
            @@TVSLIM_E
            package:com.google.android.tvlauncher
            @@TVSLIM_U
            Error: Unknown option: --query-flags
              -a <ACTION>/-d <DATA_URI> [-t <MIME_TYPE>]
            @@TVSLIM_T
            package:com.spocky.projengmenu
        """.trimIndent()

        val info = RemoteReader(FixedExecutor(output))
            .snapshot(emptyList(), setOf("com.google.android.tvlauncher"))
            .info

        assertEquals(listOf(FactoryHome("com.google.android.tvlauncher", "", active = true)), info.factoryHomes)
    }

    @Test
    fun `the Philips HOME dispatcher is not a home screen`() = runTest {
        // From a Philips Google TV TA1 (Android 14): org.droidtv.homeintentresolver receives HOME at priority 100,
        // above Google TV, and decides where the key goes.
        val output = """
            @@TVSLIM_D
            @@TVSLIM_E
            package:com.google.android.apps.tv.launcherx
            package:org.droidtv.homeintentresolver
            @@TVSLIM_H
            priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.google.android.apps.tv.launcherx/.home.HomeActivity
            @@TVSLIM_L
            com.google.android.apps.tv.launcherx/.home.HomeActivity
            @@TVSLIM_U
            8 activities found:
              Activity #0:
                priority=100 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                org.droidtv.homeintentresolver/.HomeActivity
              Activity #1:
                priority=10 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.managedprovisioning/.preprovisioning.PostEncryptionActivity
              Activity #2:
                priority=4 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.tungsten.setupwraith/.MainActivity
              Activity #3:
                priority=2 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.google.android.apps.tv.launcherx/.home.HomeActivity
              Activity #7:
                priority=-1000 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
                com.android.tv.settings/.system.FallbackHome
            @@TVSLIM_T
            package:com.netflix.ninja
        """.trimIndent()

        val info = RemoteReader(FixedExecutor(output)).snapshot(
            watchedPackages = emptyList(),
            homePackages = setOf("com.google.android.tungsten.setupwraith", "com.google.android.apps.tv.launcherx"),
        ).info

        assertEquals(listOf("com.google.android.apps.tv.launcherx"), info.factoryHomes.map { it.packageName })
        assertTrue(info.thirdPartyLaunchers.isEmpty())
    }

    @Test
    fun `the current home can be read alone, and a failed read returns nothing`() = runTest {
        val output = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\n" +
            "com.spocky.projengmenu/.ui.home.HomeActivity"
        assertEquals("com.spocky.projengmenu", RemoteReader(FixedExecutor(output)).currentHome())
        assertEquals("", RemoteReader(FixedExecutor("", code = -1)).currentHome())
    }

    @Test
    fun `a failed command does not produce fake data`() = runTest {
        val photo = RemoteReader(FixedExecutor("", code = 1)).snapshot(
            watchedPackages = listOf("com.tcl.gallery"),
            homePackages = emptySet(),
        )

        assertEquals(DeviceInfo.EMPTY, photo.info)
        assertTrue(photo.states.isEmpty())
    }

    @Test
    fun `an empty output does not pass for a TV with no packages`() = runTest {
        // What the command swallowed by the comment returned: an empty success.
        val photo = RemoteReader(FixedExecutor("")).snapshot(
            watchedPackages = listOf("com.tcl.gallery"),
            homePackages = emptySet(),
        )

        // The package is reported absent for lack of anything better, but nothing may suggest a healthy TV.
        assertEquals(PackageState.ABSENT, photo.states["com.tcl.gallery"])
        assertEquals(0, photo.info.installedPackages)
        assertFalse("No property may be made up", photo.info.model.isNotBlank())
    }
    @Test
    fun `requested permissions are told apart from those actually granted`() = runTest {
        // Real `dumpsys package` excerpt: sections are separated only by indentation, and "declared permissions"
        // lists what the app defines for others, not what it requests.
        val executor = FixedExecutor(
            """
            Permissions:
              Permission [net.jolabs40.hippietv.permission.RECEVOIR] (7f3):
                sourcePackage=net.jolabs40.hippietv.launcher.debug
            Packages:
              Package [net.jolabs40.hippietv.launcher.debug] (a1b2c3):
                userId=10123
                declared permissions:
                  net.jolabs40.hippietv.permission.RECEVOIR: prot=signature, INSTALLED
                requested permissions:
                  android.permission.INTERNET
                  android.permission.DUMP
                  android.permission.POST_NOTIFICATIONS
                install permissions:
                  android.permission.INTERNET: granted=true
                  android.permission.DUMP: granted=false
                User 0: ceDataInode=123 installed=true hidden=false
                  runtime permissions:
                    android.permission.POST_NOTIFICATIONS: granted=true, flags=[ USER_SET ]
                    android.permission.READ_MEDIA_IMAGES: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED ]
            """.trimIndent(),
        )

        val fetched = RemoteReader(executor).permissions("net.jolabs40.hippietv.launcher.debug")

        assertTrue(fetched.packageFound)
        assertTrue(fetched.isDeclared("android.permission.DUMP"))
        assertFalse(
            "DUMP is requested but not granted yet",
            fetched.isGranted("android.permission.DUMP"),
        )
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_MEDIA_IMAGES",
            ),
            fetched.granted,
        )
        assertFalse(
            "A permission the app defines is not a permission it requests",
            fetched.isDeclared("net.jolabs40.hippietv.permission.RECEVOIR"),
        )
    }

    @Test
    fun `a suspicious package name never reaches the shell`() = runTest {
        val executor = FixedExecutor("")

        val fetched = RemoteReader(executor).permissions("com.tcl.gallery; reboot")

        assertFalse(fetched.packageFound)
        assertEquals(null, executor.incoming)
    }
    @Test
    fun `an app-op mode is read in all three output forms`() = runTest {
        // The three outputs seen on a real device.
        val wasSet = RemoteReader(FixedExecutor("GET_USAGE_STATS: allow; time=+13m59s344ms ago"))
        assertEquals("allow", wasSet.appOpMode("com.example", "GET_USAGE_STATS"))

        val neverSet = RemoteReader(FixedExecutor("No operations." + System.lineSeparator() + "Default mode: default"))
        assertEquals("default", neverSet.appOpMode("com.example", "GET_USAGE_STATS"))

        val failed = RemoteReader(FixedExecutor("Error: No UID for com.example in user 0"))
        assertEquals("", failed.appOpMode("com.example", "GET_USAGE_STATS"))
    }

    @Test
    fun `an app-op with a suspicious name never reaches the shell`() = runTest {
        val executor = FixedExecutor("GET_USAGE_STATS: allow")

        val mode = RemoteReader(executor).appOpMode("com.example", "GET_USAGE_STATS; reboot")

        assertEquals("", mode)
        assertEquals(null, executor.incoming)
    }
}
