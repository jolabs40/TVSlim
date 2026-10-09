package net.jolabs40.tvslim.fichiers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheminDistantTest {

    @Test
    fun `a path is reduced to its simple form`() {
        assertEquals("/sdcard/Download", CheminDistant.normaliser("/sdcard//Download/"))
        assertEquals("/sdcard", CheminDistant.normaliser("/sdcard/Download/.."))
        assertEquals("/sdcard/Movies", CheminDistant.normaliser("sdcard/./Movies"))
        assertEquals("/", CheminDistant.normaliser("/../.."))
        assertEquals("/", CheminDistant.normaliser(""))
    }

    @Test
    fun `the root has no parent`() {
        assertEquals("/sdcard", CheminDistant.parent("/sdcard/Download"))
        assertEquals("/", CheminDistant.parent("/sdcard"))
        assertNull(CheminDistant.parent("/"))
    }

    @Test
    fun `joining does not double the root slash`() {
        assertEquals("/data", CheminDistant.joindre("/", "data"))
        assertEquals("/sdcard/Movies/a b.mkv", CheminDistant.joindre("/sdcard/Movies", "a b.mkv"))
    }

    @Test
    fun `the breadcrumb starts at the root`() {
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
    fun `a name rejects what would break the path or the folder listing`() {
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
    fun `an apostrophe reaches a command intact`() {
        assertEquals("'/sdcard/l'\\''été'", citer("/sdcard/l'été"))
        assertEquals("'a; rm -rf /'", citer("a; rm -rf /"))
        assertEquals("'\$HOME `id`'", citer("\$HOME `id`"))
    }
}
