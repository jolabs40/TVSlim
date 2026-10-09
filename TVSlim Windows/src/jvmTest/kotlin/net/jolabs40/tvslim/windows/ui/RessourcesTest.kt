package net.jolabs40.tvslim.windows.ui

import kotlinx.coroutines.test.runTest
import net.jolabs40.tvslim.windows.ressources.Res
import net.jolabs40.tvslim.windows.ressources.msg_enter_address
import net.jolabs40.tvslim.windows.ressources.msg_failure
import net.jolabs40.tvslim.windows.ressources.msg_perm_granted
import net.jolabs40.tvslim.windows.ressources.msg_perm_reopen
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
class RessourcesTest {

    private val racine = File(System.getProperty("tvslim.projet"), "src/commonMain/composeResources")

    private fun textes(dossier: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(racine, "$dossier/strings.xml"))
        val noeuds = document.getElementsByTagName("string")
        return (0 until noeuds.length).associate { i ->
            val element = noeuds.item(i) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun arguments(texte: String): List<String> =
        Regex("""%\d+\$[sd]""").findAll(texte).map { it.value }.sorted().toList()

    @Test
    fun `French and English have the same strings`() {
        val anglais = textes("values")
        val francais = textes("values-fr")

        assertEquals("Absents en français", emptySet<String>(), anglais.keys - francais.keys)
        assertEquals("Absents en anglais", emptySet<String>(), francais.keys - anglais.keys)
    }

    @Test
    fun `each translation keeps the original's arguments`() {
        val anglais = textes("values")
        val francais = textes("values-fr")

        anglais.forEach { (cle, texte) ->
            assertEquals(cle, arguments(texte), arguments(francais.getValue(cle)))
        }
    }

    @Test
    fun `an escaped apostrophe renders without its backslash`() = runTest {
        val rendu = getString(Res.string.msg_enter_address)

        assertFalse(rendu, rendu.contains('\\'))
        assertTrue(rendu, rendu.contains('\''))
    }

    @Test
    fun `a composite message renders its parts and skips blank ones`() = runTest {
        val message = MessageUi.Lignes(
            listOf(
                texte(Res.string.msg_perm_granted, "android.permission.DUMP"),
                MessageUi.Brut(""),
                texte(Res.string.msg_perm_reopen),
            ),
        )

        val lignes = message.rediger().lines()

        assertEquals(2, lignes.size)
        assertTrue(lignes.first().contains("android.permission.DUMP"))
    }

    @Test
    fun `an argument can itself be a message`() = runTest {
        val rendu = texte(Res.string.msg_failure, MessageUi.Brut("Error: unknown package")).rediger()

        assertTrue(rendu, rendu.endsWith("Error: unknown package"))
    }
}
