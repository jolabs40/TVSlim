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
 * Chaque langue doit porter exactement les mêmes textes, avec les mêmes arguments : un texte absent
 * en français retomberait sur l'anglais au milieu d'un écran, un argument oublié ferait disparaître
 * un nombre ou un nom de paquet.
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
    fun `le francais et l'anglais portent les memes textes`() {
        val anglais = textes("values")
        val francais = textes("values-fr")

        assertEquals("Absents en français", emptySet<String>(), anglais.keys - francais.keys)
        assertEquals("Absents en anglais", emptySet<String>(), francais.keys - anglais.keys)
    }

    @Test
    fun `chaque traduction garde les arguments de l'original`() {
        val anglais = textes("values")
        val francais = textes("values-fr")

        anglais.forEach { (cle, texte) ->
            assertEquals(cle, arguments(texte), arguments(francais.getValue(cle)))
        }
    }

    @Test
    fun `une apostrophe echappee s'affiche sans sa barre oblique`() = runTest {
        val rendu = getString(Res.string.msg_enter_address)

        assertFalse(rendu, rendu.contains('\\'))
        assertTrue(rendu, rendu.contains('\''))
    }

    @Test
    fun `un message compose redige ses morceaux et saute les vides`() = runTest {
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
    fun `un argument peut etre lui-meme un message`() = runTest {
        val rendu = texte(Res.string.msg_failure, MessageUi.Brut("Error: unknown package")).rediger()

        assertTrue(rendu, rendu.endsWith("Error: unknown package"))
    }
}
