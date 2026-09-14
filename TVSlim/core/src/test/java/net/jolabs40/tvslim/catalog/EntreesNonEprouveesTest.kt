package net.jolabs40.tvslim.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Une entrée décrite d'après un inventaire envoyé, sans qu'on ait vu ce que coûte sa désactivation : elle se
 * montre et se désactive une à une, mais aucun profil ne la coche.
 */
class EntreesNonEprouveesTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `une entree est eprouvee tant que le catalogue ne dit pas le contraire`() {
        val entree = json.decodeFromString(
            EntreePaquet.serializer(),
            """{"paquet":"com.tcl.pub","nom":"Pub","description":"","categorie":"bloatware_tcl"}""",
        )

        assertTrue(entree.eprouve)
    }

    @Test
    fun `un profil ne couvre jamais une entree non eprouvee, meme traduite`() {
        val catalogue = Catalogue(
            entrees = listOf(
                EntreePaquet("com.tcl.pub", "Pub", "", "pub"),
                EntreePaquet("org.droidtv.welcome", "Welcome", "", "pub", eprouve = false),
            ),
        ).traduit(Traductions(entrees = mapOf("org.droidtv.welcome" to TexteEntree(nom = "Accueil"))))

        val profil = Profil(id = "pub", nom = "Pub", description = "", categories = listOf("pub"))

        assertEquals(listOf("com.tcl.pub"), catalogue.entreesDuProfil(profil).map { it.paquet })
        // La traduction change le texte, jamais le statut.
        val traduite = catalogue.entrees.single { it.paquet == "org.droidtv.welcome" }
        assertEquals("Accueil", traduite.nom)
        assertFalse(traduite.eprouve)
    }
}
