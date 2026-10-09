package net.jolabs40.tvslim.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the catalogue translation.
 *
 * The real risk is a package added to the base file without its translation. These tests read the shipped
 * files, not sample data, so they fail as soon as one is missing.
 */
class TranslationsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val base: Catalog by lazy {
        json.decodeFromString(Catalog.serializer(), file("catalogue.json"))
    }

    private val french: Translations by lazy {
        json.decodeFromString(Translations.serializer(), file("catalogue-fr.json"))
    }

    private fun file(name: String) = File("src/main/assets/$name").readText()

    @Test
    fun `every catalog entry has a French translation`() {
        val missing = base.entries.map { it.packageName }.filterNot { it in french.entries }
        assertTrue("Entries without a French translation: $missing", missing.isEmpty())
    }

    @Test
    fun `every category, profile, setting and protected package is translated`() {
        assertTrue(
            "Untranslated categories: " +
                base.categories.map { it.id }.filterNot { it in french.categories },
            base.categories.all { it.id in french.categories },
        )
        assertTrue(
            "Untranslated profiles: " + base.profiles.map { it.id }.filterNot { it in french.profiles },
            base.profiles.all { it.id in french.profiles },
        )
        assertTrue(
            "Untranslated settings: " + base.settings.map { it.key }.filterNot { it in french.settings },
            base.settings.all { it.key in french.settings },
        )
        assertTrue(
            "Untranslated launchers: " +
                base.launchers.map { it.packageName }.filterNot { it in french.launchers },
            base.launchers.all { it.packageName in french.launchers },
        )
        assertTrue(
            "Untranslated protected packages: " +
                base.protectedPackages.map { it.packageName }.filterNot { it in french.protectedPackages },
            base.protectedPackages.all { it.packageName in french.protectedPackages },
        )
    }

    @Test
    fun `recommended launcher highlights are translated one for one`() {
        base.launchers.forEach { launcher ->
            val translated = french.launchers[launcher.packageName]?.highlights.orEmpty()
            assertEquals("Highlights of ${launcher.name}", launcher.highlights.size, translated.size)
        }
        val startlight = base.translated(french).launchers.first { it.id == "startlight" }
        assertTrue(startlight.highlights.all { it.isNotBlank() })
    }

    @Test
    fun `translation leaves non-text fields alone`() {
        val translated = base.translated(french)

        assertEquals(base.entries.size, translated.entries.size)
        assertEquals(base.protectedPackages.size, translated.protectedPackages.size)

        val katnissBase = base.entries.first { it.packageName == "com.google.android.katniss" }
        val katnissFr = translated.entries.first { it.packageName == "com.google.android.katniss" }

        assertEquals("Assistant Google", katnissFr.name)
        // Non-text fields are data and stay unchanged.
        assertEquals(katnissBase.risk, katnissFr.risk)
        assertEquals(katnissBase.sizeMb, katnissFr.sizeMb)
        assertEquals(katnissBase.category, katnissFr.category)
        assertEquals(katnissBase.order, katnissFr.order)
    }

    @Test
    fun `an entry missing from the translation falls back to English`() {
        val partial = Translations(
            language = "fr",
            entries = mapOf("com.google.android.katniss" to EntryText(name = "Assistant Google")),
        )
        val translated = base.translated(partial)

        assertEquals("Assistant Google", translated.entries.first { it.packageName == "com.google.android.katniss" }.name)
        // No translated description: the English one is kept rather than an empty string.
        assertEquals(
            base.entries.first { it.packageName == "com.google.android.katniss" }.description,
            translated.entries.first { it.packageName == "com.google.android.katniss" }.description,
        )
        // Entry missing from the translation: unchanged.
        assertEquals(
            base.entries.first { it.packageName == "com.tcl.channelplus" }.description,
            translated.entries.first { it.packageName == "com.tcl.channelplus" }.description,
        )
    }

    @Test
    fun `safeguards survive translation`() {
        val translated = base.translated(french)

        assertTrue(translated.isProtected("com.android.location.fused"))
        assertNotNull(translated.protectionReason("com.tcl.suspension"))
        // The home screen ordering is a rule, so translation must not touch it.
        val setupwraith = translated.entries.first { it.packageName == "com.google.android.tungsten.setupwraith" }
        val launcherx = translated.entries.first { it.packageName == "com.google.android.apps.tv.launcherx" }
        assertTrue(setupwraith.order < launcherx.order)
        assertTrue(setupwraith.requiresThirdPartyLauncher && launcherx.requiresThirdPartyLauncher)
    }
}
