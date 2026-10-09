package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.windows.resources.Res
import net.jolabs40.tvslim.windows.resources.msg_enter_address
import net.jolabs40.tvslim.windows.resources.msg_failure
import net.jolabs40.tvslim.windows.resources.msg_perm_granted
import net.jolabs40.tvslim.windows.resources.msg_perm_reopen
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every language must have exactly the same strings with the same arguments: a string missing in French falls
 * back to English mid-screen, and a missing argument drops a number or a package name.
 */
class ResourcesTest {

    private val root = File(System.getProperty("tvslim.project"), "src/commonMain/composeResources")

    private fun texts(folder: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(root, "$folder/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun arguments(text: String): List<String> =
        Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `French and English have the same strings`() {
        val english = texts("values")
        val french = texts("values-fr")

        assertEquals("Absents en français", emptySet<String>(), english.keys - french.keys)
        assertEquals("Absents en anglais", emptySet<String>(), french.keys - english.keys)
    }

    @Test
    fun `each translation keeps the original's arguments`() {
        val english = texts("values")
        val french = texts("values-fr")

        english.forEach { (key, text) ->
            assertEquals(key, arguments(text), arguments(french.getValue(key)))
        }
    }

    @Test
    fun `an escaped apostrophe renders without its backslash`() = runTest {
        val rendered = getString(Res.string.msg_enter_address)

        assertFalse(rendered, rendered.contains('\\'))
        assertTrue(rendered, rendered.contains('\''))
    }

    @Test
    fun `a composite message renders its parts and skips blank ones`() = runTest {
        val message = UiMessage.Lines(
            listOf(
                text(Res.string.msg_perm_granted, "android.permission.DUMP"),
                UiMessage.Raw(""),
                text(Res.string.msg_perm_reopen),
            ),
        )

        val lines = message.phrase().lines()

        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("android.permission.DUMP"))
    }

    @Test
    fun `an argument can itself be a message`() = runTest {
        val rendered = text(Res.string.msg_failure, UiMessage.Raw("Error: unknown package")).phrase()

        assertTrue(rendered, rendered.endsWith("Error: unknown package"))
    }
}
