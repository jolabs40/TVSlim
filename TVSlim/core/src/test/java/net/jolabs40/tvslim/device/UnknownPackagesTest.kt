package net.jolabs40.tvslim.device

import net.jolabs40.tvslim.catalog.Catalog
import net.jolabs40.tvslim.catalog.PackageEntry
import net.jolabs40.tvslim.catalog.KnownLauncher
import net.jolabs40.tvslim.catalog.RecommendedLauncher
import net.jolabs40.tvslim.catalog.ProtectedPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packages missing from the catalogue: detection, grouping by origin, and the exported inventory. Read-only. */
class UnknownPackagesTest {

    @Test
    fun `a package origin is guessed from its name and the device brand`() {
        assertEquals(PackageOrigin.ANDROID, PackageOrigin.of("com.google.android.katniss", Manufacturer.PHILIPS))
        assertEquals(PackageOrigin.ANDROID, PackageOrigin.of("com.android.tv.settings", Manufacturer.TCL))
        assertEquals(PackageOrigin.ANDROID, PackageOrigin.of("android", null))

        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("org.droidtv.playtv", Manufacturer.PHILIPS))
        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("com.tcl.tv.tclhome_passive", Manufacturer.TCL))
        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("com.sony.dtv.tvx", Manufacturer.SONY))
        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("com.nvidia.ota", Manufacturer.NVIDIA))
        // Chipmaker packages count as manufacturer packages, whatever the brand.
        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("com.mediatek.wwtv.tvcenter", Manufacturer.PHILIPS))
        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("com.droidlogic.tvinput", null))

        assertEquals(PackageOrigin.OTHER, PackageOrigin.of("com.netflix.ninja", Manufacturer.TCL))
        // Another brand's package is not this device's manufacturer package.
        assertEquals(PackageOrigin.OTHER, PackageOrigin.of("org.droidtv.playtv", Manufacturer.TCL))
        assertEquals(PackageOrigin.OTHER, PackageOrigin.of("com.amazon.amazonvideo.livingroom", Manufacturer.TCL))
        assertEquals(PackageOrigin.MAKER, PackageOrigin.of("com.amazon.tv.launcher", Manufacturer.AMAZON))
    }

    @Test
    fun `a catalogue entry origin follows the brand the catalogue gives it`() {
        fun entry(brand: String, packageName: String = "a.b.c") =
            PackageEntry(packageName = packageName, name = "", description = "", category = "", brand = brand)

        assertEquals(PackageOrigin.ANDROID, entry("Google").origin)
        assertEquals(PackageOrigin.ANDROID, entry("AOSP").origin)
        assertEquals(PackageOrigin.MAKER, entry("TCL").origin)
        assertEquals(PackageOrigin.MAKER, entry("MediaTek").origin)
        assertEquals(PackageOrigin.OTHER, entry("Third party").origin)
        assertEquals(PackageOrigin.ANDROID, entry("", packageName = "com.android.vending").origin)
    }

    @Test
    fun `only what the catalogue lacks is unknown, sorted from manufacturer to other`() {
        val catalog = Catalog(
            entries = listOf(PackageEntry("com.tcl.pub", "Pub", "", "test")),
            protectedPackages = listOf(ProtectedPackage("com.tcl.tv", "Tuner.")),
            launchers = listOf(
                RecommendedLauncher("net.jolabs40.startlight", "Startlight", "", variants = listOf("net.jolabs40.startlight.debug")),
            ),
            knownLaunchers = listOf(KnownLauncher("projectivy", "Projectivy", listOf("com.spocky.projengmenu"))),
        )
        val system = mapOf(
            "com.tcl.pub" to PackageState.DISABLED,
            "com.tcl.tv" to PackageState.ACTIVE,
            "net.jolabs40.startlight.debug" to PackageState.ACTIVE,
            "com.spocky.projengmenu" to PackageState.ACTIVE,
            "com.netflix.ninja" to PackageState.ACTIVE,
            "com.google.android.katniss" to PackageState.ACTIVE,
            "com.tcl.tv.tclhome_passive" to PackageState.DISABLED,
            "com.mediatek.wwtv.tvcenter" to PackageState.ACTIVE,
        )

        val unknowns = catalog.unknownPackages(system, Manufacturer.TCL)

        assertEquals(
            listOf(
                "com.mediatek.wwtv.tvcenter",
                "com.tcl.tv.tclhome_passive",
                "com.google.android.katniss",
                "com.netflix.ninja",
            ),
            unknowns.map { it.packageName },
        )
        assertEquals(PackageState.DISABLED, unknowns.first { it.packageName == "com.tcl.tv.tclhome_passive" }.state)
        assertEquals("com.mediatek", unknowns.first().family)

        // The framework and its overlays form a single family, not one per package.
        fun family(packageName: String) = UnknownPackage(packageName, PackageState.ACTIVE, PackageOrigin.ANDROID).family
        assertEquals("android", family("android"))
        assertEquals("android", family("android.auto_generated_rro_vendor__"))
        assertEquals("android", family("android.autoinstalls.config.google.gtvpai"))
        assertEquals("org.droidtv", family("org.droidtv.playtv"))
    }

    @Test
    fun `the inventory names the device and groups each package by origin and family`() {
        val philips = DeviceInfo(
            brand = "TPV",
            retailBrand = "Philips",
            model = "55PUS8807/12",
            androidVersion = "11",
            build = "TPM211E",
        )
        val unknowns = listOf(
            UnknownPackage("org.droidtv.playtv", PackageState.ACTIVE, PackageOrigin.MAKER),
            UnknownPackage("org.droidtv.welcome", PackageState.DISABLED, PackageOrigin.MAKER),
            UnknownPackage("com.google.android.katniss", PackageState.ACTIVE, PackageOrigin.ANDROID),
        )
        fun entry(packageName: String, brand: String) =
            PackageEntry(packageName = packageName, name = "", description = "", category = "", brand = brand)

        val mb = 1024L * 1024
        val report = UnknownsReport.markdown(
            info = philips,
            unknowns = unknowns,
            application = "TV Slim pour Windows 1.2.0",
            survey = UnknownsSurvey(
                hints = mapOf(
                    "org.droidtv.playtv" to PackageHints(
                        path = "/system/priv-app/PlayTv/PlayTv.apk",
                        uid = 1000,
                        declarations = setOf(SensitiveDeclaration.BOOT, SensitiveDeclaration.TV_INPUT),
                        icon = true,
                    ),
                    "com.google.android.katniss" to PackageHints(
                        path = "/product/priv-app/Katniss/Katniss.apk",
                        updated = true,
                        uid = 10036,
                    ),
                ),
                memory = MemoryBreakdown(
                    totalKb = 2_000_000,
                    processes = listOf(
                        MemoryProcess("org.droidtv.playtv", pid = 1200, kilobytes = 40_960),
                        MemoryProcess("org.droidtv.playtv:tuner", pid = 1201, kilobytes = 10_240),
                    ),
                ),
                storage = StorageBreakdown(
                    applications = listOf(ApplicationStorage("org.droidtv.playtv", 50 * mb, 2 * mb, 0)),
                ),
                firmware = Firmware(
                    fingerprint = "Philips/PH8M_EU/PH8M:11/RTT2.211108.001/TPM211E:user/release-keys",
                    product = "PH8M_EU",
                    factoryLanguage = "fr-FR",
                ),
            ),
            fromCatalog = mapOf(
                entry("com.netflix.ninja", "Third party") to PackageState.ACTIVE,
                entry("com.google.android.tvrecommendations", "Google") to PackageState.DISABLED,
                entry("com.tcl.pub", "TCL") to PackageState.ABSENT,
            ),
        )

        assertTrue(report, report.contains("- Appareil : Philips 55PUS8807/12"))
        assertTrue(report, report.contains("- Android : 11 (TPM211E)"))
        assertTrue(
            report,
            report.contains(
                "- Firmware : produit `PH8M_EU`, langue d'usine fr-FR, " +
                    "empreinte `Philips/PH8M_EU/PH8M:11/RTT2.211108.001/TPM211E:user/release-keys`\n",
            ),
        )
        assertTrue(report, report.contains("constructeur 2, Android 1, autres 0"))
        assertTrue(report, report.contains("- Avec les droits du système : 1 ; avec une déclaration sensible : 1"))
        assertTrue(report, report.contains("- Déjà au catalogue : 2 présents — actifs 1, désactivés 1\n"))
        assertTrue(report, report.contains("- Lu sur l'appareil : indices ADB, mémoire vive, stockage, firmware\n"))
        assertTrue(report, report.indexOf("## Constructeur") < report.indexOf("## Android"))
        assertTrue(report, report.contains("### org.droidtv (2)"))
        // Memory sums the package's processes; declarations are listed most sensitive first.
        assertTrue(
            report,
            report.contains("| `org.droidtv.playtv` | actif | system/priv-app | système | entrée TV, démarrage | oui | 50 Mo | 52 Mo |"),
        )
        assertTrue(report, report.contains("| `org.droidtv.welcome` | désactivé | — | — | — | — | — | — |"))
        assertTrue(
            report,
            report.contains("| `com.google.android.katniss` | actif | product/priv-app, mise à jour | appli | — | non | — | — |"),
        )

        // Catalogue entries on the device come after the unknown ones, sorted by name; absent ones are left out.
        assertTrue(report, report.indexOf("## Déjà au catalogue (2)") > report.indexOf("## Android"))
        assertTrue(
            report,
            report.contains(
                "| `com.google.android.tvrecommendations` | Google | désactivé |\n| `com.netflix.ninja` | Third party | actif |\n",
            ),
        )
        assertFalse(report, report.contains("com.tcl.pub"))

        // Nothing extra read: the inventory is still written, and says what is missing.
        val bare = UnknownsReport.markdown(philips, unknowns, "TV Slim Remote 1.2.0")
        assertTrue(
            bare,
            bare.contains("- Lu sur l'appareil : la seule liste des paquets ; illisible : indices ADB, mémoire vive, stockage, firmware"),
        )
        assertFalse(bare, bare.contains("droits du système :"))
        assertFalse(bare, bare.contains("- Firmware"))
        assertFalse(bare, bare.contains("Déjà au catalogue"))
        assertEquals("TVSlim-inconnus-Philips-55PUS8807-12-2026-09-13.md",
            UnknownsReport.suggestedName(philips, java.time.LocalDate.of(2026, 9, 13)))
    }

    @Test
    fun `firmware is read property by property, and an empty value shifts nothing`() {
        // Real output from the Android TV 16 emulator, with the product emptied for the test.
        val firmware = FirmwareReading.parse(
            """
            @@TVSLIM_FINGERPRINT
            google/sdk_google_atv_x86/emulator_x86_arm:16/BT2A.251018.001.A1/14340881:user/dev-keys
            @@TVSLIM_PRODUCT

            @@TVSLIM_LANGUAGE
            en-US
            """.trimIndent(),
        )

        assertEquals("google/sdk_google_atv_x86/emulator_x86_arm:16/BT2A.251018.001.A1/14340881:user/dev-keys", firmware.fingerprint)
        assertEquals("", firmware.product)
        assertEquals("en-US", firmware.factoryLanguage)
        assertTrue(firmware.populated)
        assertFalse(FirmwareReading.parse("").populated)
    }
}
