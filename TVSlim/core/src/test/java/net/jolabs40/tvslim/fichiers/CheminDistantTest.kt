package net.jolabs40.tvslim.fichiers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les chemins du téléviseur : leur forme simple, leur parent, et ce qui entre intact dans une commande. */
class CheminDistantTest {

    @Test
    fun `un chemin se ramene a sa forme simple`() {
        assertEquals("/sdcard/Download", CheminDistant.normaliser("/sdcard//Download/"))
        assertEquals("/sdcard", CheminDistant.normaliser("/sdcard/Download/.."))
        assertEquals("/sdcard/Movies", CheminDistant.normaliser("sdcard/./Movies"))
        assertEquals("/", CheminDistant.normaliser("/../.."))
        assertEquals("/", CheminDistant.normaliser(""))
    }

    @Test
    fun `le parent de la racine n'existe pas`() {
        assertEquals("/sdcard", CheminDistant.parent("/sdcard/Download"))
        assertEquals("/", CheminDistant.parent("/sdcard"))
        assertNull(CheminDistant.parent("/"))
    }

    @Test
    fun `joindre ne double pas la barre de la racine`() {
        assertEquals("/data", CheminDistant.joindre("/", "data"))
        assertEquals("/sdcard/Movies/a b.mkv", CheminDistant.joindre("/sdcard/Movies", "a b.mkv"))
    }

    @Test
    fun `le fil d'Ariane part de la racine`() {
        assertEquals(
            listOf(
                EtapeChemin("/", "/"),
                EtapeChemin("sdcard", "/sdcard"),
                EtapeChemin("Android", "/sdcard/Android"),
                EtapeChemin("data", "/sdcard/Android/data"),
            ),
            CheminDistant.etapes("/sdcard/Android/data"),
        )
        assertEquals(listOf(EtapeChemin("/", "/")), CheminDistant.etapes("/"))
    }

    @Test
    fun `un nom refuse ce qui casserait le chemin ou la lecture du dossier`() {
        assertTrue(CheminDistant.nomValide("Vacances d'été 2024.mkv"))
        assertTrue(CheminDistant.nomValide(".kodi"))
        assertFalse(CheminDistant.nomValide(""))
        assertFalse(CheminDistant.nomValide("."))
        assertFalse(CheminDistant.nomValide(".."))
        assertFalse(CheminDistant.nomValide("a/b"))
        assertFalse(CheminDistant.nomValide("ligne\nsuivante"))
        assertFalse(CheminDistant.nomValide("é".repeat(128)))
        assertTrue(CheminDistant.nomValide("é".repeat(127)))
    }

    @Test
    fun `une apostrophe entre intacte dans une commande`() {
        assertEquals("'/sdcard/l'\\''été'", citer("/sdcard/l'été"))
        assertEquals("'a; rm -rf /'", citer("a; rm -rf /"))
        assertEquals("'\$HOME `id`'", citer("\$HOME `id`"))
    }
}
