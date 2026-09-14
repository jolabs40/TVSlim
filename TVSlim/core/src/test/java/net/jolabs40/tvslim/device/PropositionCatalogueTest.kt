package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Quand proposer un inventaire au catalogue, et le formulaire qu'on ouvre pour cela. */
class PropositionCatalogueTest {

    @Test
    fun `seuls les paquets du constructeur valent une proposition`() {
        fun inconnu(origine: OriginePaquet) = PaquetInconnu("a.b.${origine.name.lowercase()}", EtatPaquet.ACTIF, origine)

        assertFalse(PropositionCatalogue.aProposer(emptyList()))
        assertFalse(PropositionCatalogue.aProposer(listOf(inconnu(OriginePaquet.ANDROID), inconnu(OriginePaquet.AUTRE))))
        assertTrue(PropositionCatalogue.aProposer(listOf(inconnu(OriginePaquet.ANDROID), inconnu(OriginePaquet.CONSTRUCTEUR))))
    }

    @Test
    fun `le lien ouvre le modele, titre et appareil remplis et encodes`() {
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
        // Un appareil qu'on n'a pas su lire : le formulaire s'ouvre quand même, sans champ vide dans l'adresse.
        assertEquals(
            "https://github.com/autre/depot/issues/new?template=nouvel-appareil.yml&title=Catalogue%3A%20%3F",
            PropositionCatalogue.lien(InfosAppareil.VIDE, depot = "autre/depot"),
        )
    }

    @Test
    fun `le modele d'issue existe, avec le champ que le lien remplit`() {
        // Les deux builds lancent les tests du noyau depuis TVSlim/core : la racine du dépôt est deux crans au-dessus.
        val modele = File("../../.github/ISSUE_TEMPLATE/${PropositionCatalogue.MODELE}")

        assertTrue("Modèle d'issue introuvable : ${modele.absolutePath}", modele.isFile)
        assertTrue(modele.readText().contains("id: device"))
    }
}
