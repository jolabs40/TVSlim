package net.jolabs40.tvslim.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Garde-fou de la traduction du catalogue.
 *
 * Le vrai risque n'est pas que la fusion soit fausse — elle tient en quelques lignes — mais
 * qu'on ajoute un paquet au fichier de base en oubliant sa traduction. Ces tests lisent les
 * fichiers livrés, pas des données de démonstration : ils échouent le jour où l'oubli arrive.
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
    fun `chaque entree du catalogue a sa traduction francaise`() {
        val manquantes = base.entrees.map { it.paquet }.filterNot { it in francais.entrees }
        assertTrue("Entrées sans traduction française : $manquantes", manquantes.isEmpty())
    }

    @Test
    fun `chaque categorie, profil, reglage et paquet protege est traduit`() {
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
            "Paquets protégés non traduits : " +
                base.proteges.map { it.paquet }.filterNot { it in francais.proteges },
            base.proteges.all { it.paquet in francais.proteges },
        )
    }

    @Test
    fun `la traduction ne traduit pas ce qui ne doit pas l'etre`() {
        val traduit = base.traduit(francais)

        assertEquals(base.entrees.size, traduit.entrees.size)
        assertEquals(base.proteges.size, traduit.proteges.size)

        val katnissBase = base.entrees.first { it.paquet == "com.google.android.katniss" }
        val katnissFr = traduit.entrees.first { it.paquet == "com.google.android.katniss" }

        assertEquals("Assistant Google", katnissFr.nom)
        // Tout ce qui n'est pas du texte reste intact : c'est de la donnée, pas de la traduction.
        assertEquals(katnissBase.risque, katnissFr.risque)
        assertEquals(katnissBase.tailleMo, katnissFr.tailleMo)
        assertEquals(katnissBase.categorie, katnissFr.categorie)
        assertEquals(katnissBase.ordre, katnissFr.ordre)
    }

    @Test
    fun `une entree absente de la traduction retombe sur l'anglais`() {
        val partielle = Traductions(
            langue = "fr",
            entrees = mapOf("com.google.android.katniss" to TexteEntree(nom = "Assistant Google")),
        )
        val traduit = base.traduit(partielle)

        assertEquals("Assistant Google", traduit.entrees.first { it.paquet == "com.google.android.katniss" }.nom)
        // Description non fournie : l'anglais est conservé plutôt qu'un texte vide.
        assertEquals(
            base.entrees.first { it.paquet == "com.google.android.katniss" }.description,
            traduit.entrees.first { it.paquet == "com.google.android.katniss" }.description,
        )
        // Entrée absente de la traduction : inchangée.
        assertEquals(
            base.entrees.first { it.paquet == "com.tcl.channelplus" }.description,
            traduit.entrees.first { it.paquet == "com.tcl.channelplus" }.description,
        )
    }

    @Test
    fun `les garde-fous survivent a la traduction`() {
        val traduit = base.traduit(francais)

        assertTrue(traduit.estProtege("com.android.location.fused"))
        assertNotNull(traduit.motifProtection("com.tcl.suspension"))
        // L'ordre d'application de l'écran d'accueil est une règle, pas un texte.
        val setupwraith = traduit.entrees.first { it.paquet == "com.google.android.tungsten.setupwraith" }
        val launcherx = traduit.entrees.first { it.paquet == "com.google.android.apps.tv.launcherx" }
        assertTrue(setupwraith.ordre < launcherx.ordre)
        assertTrue(setupwraith.requiertLauncherTiers && launcherx.requiertLauncherTiers)
    }
}
