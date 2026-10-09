package net.jolabs40.tvslim.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test
    fun `a path is reduced to its simple form`() {
        assertEquals("/sdcard/Download", RemotePath.normalize("/sdcard//Download/"))
        assertEquals("/sdcard", RemotePath.normalize("/sdcard/Download/.."))
        assertEquals("/sdcard/Movies", RemotePath.normalize("sdcard/./Movies"))
        assertEquals("/", RemotePath.normalize("/../.."))
        assertEquals("/", RemotePath.normalize(""))
    }

    @Test
    fun `the root has no parent`() {
        assertEquals("/sdcard", RemotePath.parent("/sdcard/Download"))
        assertEquals("/", RemotePath.parent("/sdcard"))
        assertNull(RemotePath.parent("/"))
    }

    @Test
    fun `joining does not double the root slash`() {
        assertEquals("/data", RemotePath.join("/", "data"))
        assertEquals("/sdcard/Movies/a b.mkv", RemotePath.join("/sdcard/Movies", "a b.mkv"))
    }

    @Test
    fun `the breadcrumb starts at the root`() {
        assertEquals(
            listOf(
                PathStep("/", "/"),
                PathStep("sdcard", "/sdcard"),
                PathStep("Android", "/sdcard/Android"),
                PathStep("data", "/sdcard/Android/data"),
            ),
            RemotePath.steps("/sdcard/Android/data"),
        )
        assertEquals(listOf(PathStep("/", "/")), RemotePath.steps("/"))
    }

    @Test
    fun `a name rejects what would break the path or the folder listing`() {
        assertTrue(RemotePath.isValidName("Vacances d'été 2024.mkv"))
        assertTrue(RemotePath.isValidName(".kodi"))
        assertFalse(RemotePath.isValidName(""))
        assertFalse(RemotePath.isValidName("."))
        assertFalse(RemotePath.isValidName(".."))
        assertFalse(RemotePath.isValidName("a/b"))
        assertFalse(RemotePath.isValidName("ligne\nsuivante"))
        assertFalse(RemotePath.isValidName("é".repeat(128)))
        assertTrue(RemotePath.isValidName("é".repeat(127)))
    }

    @Test
    fun `an apostrophe reaches a command intact`() {
        assertEquals("'/sdcard/l'\\''été'", quote("/sdcard/l'été"))
        assertEquals("'a; rm -rf /'", quote("a; rm -rf /"))
        assertEquals("'\$HOME `id`'", quote("\$HOME `id`"))
    }
}
