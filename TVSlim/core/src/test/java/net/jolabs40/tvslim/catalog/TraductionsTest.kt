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
class TraductionsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val base: Catalogue by lazy {
        json.decodeFromString(Catalogue.serializer(), fichier("catalogue.json"))
    }

    private val francais: Traductions by lazy {
        json.decodeFromString(Traductions.serializer(), fichier("catalogue-fr.json"))
    }

    private fun fichier(nom: String) = File("src/main/assets/$nom").readText()

    @Test
    fun `every catalog entry has a French translation`() {
        val manquantes = base.entrees.map { it.paquet }.filterNot { it in francais.entrees }
        assertTrue("Entrées sans traduction française : $manquantes", manquantes.isEmpty())
    }

    @Test
    fun `every category, profile, setting and protected package is translated`() {
        assertTrue(
            "Catégories non traduites : " +
                base.categories.map { it.id }.filterNot { it in francais.categories },
            base.categories.all { it.id in francais.categories },
        )
        assertTrue(
            "Profils non traduits : " + base.profils.map { it.id }.filterNot { it in francais.profils },
            base.profils.all { it.id in francais.profils },
        )
        assertTrue(
            "Réglages non traduits : " + base.reglages.map { it.cle }.filterNot { it in francais.reglages },
            base.reglages.all { it.cle in francais.reglages },
        )
        assertTrue(
            "Launchers non traduits : " +
                base.launchers.map { it.paquet }.filterNot { it in francais.launchers },
            base.launchers.all { it.paquet in francais.launchers },
        )
        assertTrue(
            "Paquets protégés non traduits : " +
                base.proteges.map { it.paquet }.filterNot { it in francais.proteges },
            base.proteges.all { it.paquet in francais.proteges },
        )
    }

    @Test
    fun `recommended launcher highlights are translated one for one`() {
        base.launchers.forEach { launcher ->
            val traduits = francais.launchers[launcher.paquet]?.pointsForts.orEmpty()
            assertEquals("Points forts de ${launcher.nom}", launcher.pointsForts.size, traduits.size)
        }
        val startlight = base.traduit(francais).launchers.first { it.id == "startlight" }
        assertTrue(startlight.pointsForts.all { it.isNotBlank() })
    }

    @Test
    fun `translation leaves non-text fields alone`() {
        val traduit = base.traduit(francais)

        assertEquals(base.entrees.size, traduit.entrees.size)
        assertEquals(base.proteges.size, traduit.proteges.size)

        val katnissBase = base.entrees.first { it.paquet == "com.google.android.katniss" }
        val katnissFr = traduit.entrees.first { it.paquet == "com.google.android.katniss" }

        assertEquals("Assistant Google", katnissFr.nom)
        // Non-text fields are data and stay unchanged.
        assertEquals(katnissBase.risque, katnissFr.risque)
        assertEquals(katnissBase.tailleMo, katnissFr.tailleMo)
        assertEquals(katnissBase.categorie, katnissFr.categorie)
        assertEquals(katnissBase.ordre, katnissFr.ordre)
    }

    @Test
    fun `an entry missing from the translation falls back to English`() {
        val partielle = Traductions(
            langue = "fr",
            entrees = mapOf("com.google.android.katniss" to TexteEntree(nom = "Assistant Google")),
        )
        val traduit = base.traduit(partielle)

        assertEquals("Assistant Google", traduit.entrees.first { it.paquet == "com.google.android.katniss" }.nom)
        // No translated description: the English one is kept rather than an empty string.
        assertEquals(
            base.entrees.first { it.paquet == "com.google.android.katniss" }.description,
            traduit.entrees.first { it.paquet == "com.google.android.katniss" }.description,
        )
        // Entry missing from the translation: unchanged.
        assertEquals(
            base.entrees.first { it.paquet == "com.tcl.channelplus" }.description,
            traduit.entrees.first { it.paquet == "com.tcl.channelplus" }.description,
        )
    }

    @Test
    fun `safeguards survive translation`() {
        val traduit = base.traduit(francais)

        assertTrue(traduit.estProtege("com.android.location.fused"))
        assertNotNull(traduit.motifProtection("com.tcl.suspension"))
        // The home screen ordering is a rule, so translation must not touch it.
        val setupwraith = traduit.entrees.first { it.paquet == "com.google.android.tungsten.setupwraith" }
        val launcherx = traduit.entrees.first { it.paquet == "com.google.android.apps.tv.launcherx" }
        assertTrue(setupwraith.ordre < launcherx.ordre)
        assertTrue(setupwraith.requiertLauncherTiers && launcherx.requiertLauncherTiers)
    }
}
