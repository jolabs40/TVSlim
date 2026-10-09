package net.jolabs40.tvslim.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Untested entries, described from a submitted inventory without seeing what disabling them breaks. They can be
 * disabled one by one, but no profile selects them.
 */
class UntestedEntriesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `an entry is tested unless the catalog says otherwise`() {
        val entry = json.decodeFromString(
            PackageEntry.serializer(),
            """{"paquet":"com.tcl.pub","nom":"Pub","description":"","categorie":"bloatware_tcl"}""",
        )

        assertTrue(entry.tested)
    }

    @Test
    fun `a profile never covers an untested entry, even once translated`() {
        val catalog = Catalog(
            entries = listOf(
                PackageEntry("com.tcl.pub", "Pub", "", "pub"),
                PackageEntry("org.droidtv.welcome", "Welcome", "", "pub", tested = false),
            ),
        ).translated(Translations(entries = mapOf("org.droidtv.welcome" to EntryText(name = "Accueil"))))

        val profile = Profile(id = "pub", name = "Pub", description = "", categories = listOf("pub"))

        assertEquals(listOf("com.tcl.pub"), catalog.profileEntries(profile).map { it.packageName })
        // Translation changes the text, never the status.
        val translated = catalog.entries.single { it.packageName == "org.droidtv.welcome" }
        assertEquals("Accueil", translated.name)
        assertFalse(translated.tested)
    }
}
