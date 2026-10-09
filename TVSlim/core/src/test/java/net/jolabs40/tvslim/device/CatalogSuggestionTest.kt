package net.jolabs40.tvslim.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** When to suggest an inventory for the catalogue, and the issue form it opens. */
class CatalogSuggestionTest {

    @Test
    fun `only manufacturer packages warrant a suggestion`() {
        fun unknown(origin: PackageOrigin) = UnknownPackage("a.b.${origin.name.lowercase()}", PackageState.ACTIVE, origin)

        assertFalse(CatalogSuggestion.shouldOffer(emptyList()))
        assertFalse(CatalogSuggestion.shouldOffer(listOf(unknown(PackageOrigin.ANDROID), unknown(PackageOrigin.OTHER))))
        assertTrue(CatalogSuggestion.shouldOffer(listOf(unknown(PackageOrigin.ANDROID), unknown(PackageOrigin.MAKER))))
    }

    @Test
    fun `the link opens the template with title and device filled in and encoded`() {
        val philips = DeviceInfo(
            brand = "TPV",
            retailBrand = "Philips",
            model = "55PUS8807/12",
            androidVersion = "11",
        )

        assertEquals(
            "https://github.com/jolabs40/TVSlim/issues/new?template=nouvel-appareil.yml" +
                "&title=Catalogue%3A%20Philips%2055PUS8807%2F12%20%28Android%2011%29" +
                "&device=Philips%2055PUS8807%2F12",
            CatalogSuggestion.link(philips),
        )
        // Unreadable device: the form still opens, with no empty field in the URL.
        assertEquals(
            "https://github.com/autre/depot/issues/new?template=nouvel-appareil.yml&title=Catalogue%3A%20%3F",
            CatalogSuggestion.link(DeviceInfo.EMPTY, repository = "autre/depot"),
        )
    }

    @Test
    fun `the issue template exists, with the field the link fills in`() {
        // Both builds run core tests from TVSlim/core, so the repository root is two levels up.
        val model = File("../../.github/ISSUE_TEMPLATE/${CatalogSuggestion.ISSUE_TEMPLATE}")

        assertTrue("Modèle d'issue introuvable : ${model.absolutePath}", model.isFile)
        assertTrue(model.readText().contains("id: device"))
    }
}
