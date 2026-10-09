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
class EntreesNonEprouveesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `an entry is tested unless the catalog says otherwise`() {
        val entree = json.decodeFromString(
            EntreePaquet.serializer(),
            """{"paquet":"com.tcl.pub","nom":"Pub","description":"","categorie":"bloatware_tcl"}""",
        )

        assertTrue(entree.eprouve)
    }

    @Test
    fun `a profile never covers an untested entry, even once translated`() {
        val catalogue = Catalogue(
            entrees = listOf(
                EntreePaquet("com.tcl.pub", "Pub", "", "pub"),
                EntreePaquet("org.droidtv.welcome", "Welcome", "", "pub", eprouve = false),
            ),
        ).traduit(Traductions(entrees = mapOf("org.droidtv.welcome" to TexteEntree(nom = "Accueil"))))

        val profil = Profil(id = "pub", nom = "Pub", description = "", categories = listOf("pub"))

        assertEquals(listOf("com.tcl.pub"), catalogue.entreesDuProfil(profil).map { it.paquet })
        // Translation changes the text, never the status.
        val traduite = catalogue.entrees.single { it.paquet == "org.droidtv.welcome" }
        assertEquals("Accueil", traduite.nom)
        assertFalse(traduite.eprouve)
    }
}
