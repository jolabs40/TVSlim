package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** When to suggest an inventory for the catalogue, and the issue form it opens. */
class PropositionCatalogueTest {

    @Test
    fun `only manufacturer packages warrant a suggestion`() {
        fun inconnu(origine: OriginePaquet) = PaquetInconnu("a.b.${origine.name.lowercase()}", EtatPaquet.ACTIF, origine)

        assertFalse(PropositionCatalogue.aProposer(emptyList()))
        assertFalse(PropositionCatalogue.aProposer(listOf(inconnu(OriginePaquet.ANDROID), inconnu(OriginePaquet.AUTRE))))
        assertTrue(PropositionCatalogue.aProposer(listOf(inconnu(OriginePaquet.ANDROID), inconnu(OriginePaquet.CONSTRUCTEUR))))
    }

    @Test
    fun `the link opens the template with title and device filled in and encoded`() {
        val philips = InfosAppareil(
            marque = "TPV",
            marqueCommerciale = "Philips",
            modele = "55PUS8807/12",
            versionAndroid = "11",
        )

        assertEquals(
            "https://github.com/jolabs40/TVSlim/issues/new?template=nouvel-appareil.yml" +
                "&title=Catalogue%3A%20Philips%2055PUS8807%2F12%20%28Android%2011%29" +
                "&device=Philips%2055PUS8807%2F12",
            PropositionCatalogue.lien(philips),
        )
        // Unreadable device: the form still opens, with no empty field in the URL.
        assertEquals(
            "https://github.com/autre/depot/issues/new?template=nouvel-appareil.yml&title=Catalogue%3A%20%3F",
            PropositionCatalogue.lien(InfosAppareil.VIDE, depot = "autre/depot"),
        )
    }

    @Test
    fun `the issue template exists, with the field the link fills in`() {
        // Both builds run core tests from TVSlim/core, so the repository root is two levels up.
        val modele = File("../../.github/ISSUE_TEMPLATE/${PropositionCatalogue.MODELE}")

        assertTrue("Modèle d'issue introuvable : ${modele.absolutePath}", modele.isFile)
        assertTrue(modele.readText().contains("id: device"))
    }
}
