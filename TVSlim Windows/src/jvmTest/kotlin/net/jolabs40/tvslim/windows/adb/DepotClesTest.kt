package net.jolabs40.tvslim.windows.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * La clé ADB vaut un accès shell à chaque téléviseur autorisé : elle ne doit jamais rester en clair
 * sur le disque, et elle doit se relire à l'identique — sans quoi chaque téléviseur redemanderait
 * l'autorisation à la télécommande.
 */
class DepotClesTest {

    @get:Rule
    val dossier = TemporaryFolder()

    /** Réversible et visible : on vérifie que le dépôt passe bien par la protection. */
    private object ProtectionInversee : ProtectionDonnees {
        override fun proteger(donnees: ByteArray) = donnees.reversedArray() + MARQUE
        override fun lever(protegees: ByteArray): ByteArray {
            require(protegees.takeLast(MARQUE.size).toByteArray().contentEquals(MARQUE)) { "non protégé" }
            return protegees.copyOfRange(0, protegees.size - MARQUE.size).reversedArray()
        }

        private val MARQUE = "#protege".toByteArray()
    }

    /** Le contenu des deux fichiers de la paire : s'il ne bouge pas, la clé a été relue, pas refaite. */
    private fun empreinte(cles: File) =
        File(cles, "adbkey.pub").readBytes() + File(cles, "adbkey.dpapi").readBytes()

    @Test
    fun `la paire creee se relit a l'identique depuis un autre depot`() {
        val cles = dossier.newFolder("cles")
        assertNotNull(DepotCles(cles, ProtectionInversee).paire())
        val avant = empreinte(cles)

        assertNotNull(DepotCles(cles, ProtectionInversee).paire())

        assertArrayEquals(avant, empreinte(cles))
        assertFalse(cles.listFiles()!!.any { it.name.contains("illisible") })
    }

    @Test
    fun `aucune cle en clair ne reste sur le disque`() {
        val cles = dossier.newFolder("cles")
        DepotCles(cles, ProtectionInversee).paire()

        val fichiers = cles.walkTopDown().filter { it.isFile }.map { it.name }.toSet()
        assertEquals(setOf("adbkey.dpapi", "adbkey.pub"), fichiers)

        val prive = File(cles, "adbkey.dpapi").readBytes()
        assertFalse(String(prive, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
        // Le fichier ne se déchiffre qu'en passant par la protection.
        assertNotNull(clePriveeDepuisDer(ProtectionInversee.lever(prive)))
    }

    @Test
    fun `une cle illisible est mise de cote, pas effacee`() {
        val cles = dossier.newFolder("cles")
        DepotCles(cles, ProtectionInversee).paire()
        File(cles, "adbkey.dpapi").writeText("abîmé")

        val nouvelle = DepotCles(cles, ProtectionInversee).paire()

        assertNotNull(nouvelle)
        assertTrue(cles.listFiles()!!.any { it.name.startsWith("adbkey.dpapi.illisible-") })
        assertTrue(File(cles, "adbkey.dpapi").exists())
    }

    @Test
    fun `DPAPI chiffre et dechiffre sous ce compte`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val secret = "une clé qui ne doit pas se lire".toByteArray()

        val protege = ProtectionDpapi.proteger(secret)

        assertFalse(protege.contentEquals(secret))
        assertArrayEquals(secret, ProtectionDpapi.lever(protege))
    }

    @Test
    fun `la paire reelle se range et se relit avec DPAPI`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val cles = dossier.newFolder("cles")
        assertNotNull(DepotCles(cles).paire())
        val avant = empreinte(cles)

        assertNotNull(DepotCles(cles).paire())

        assertArrayEquals(avant, empreinte(cles))
        assertFalse(cles.listFiles()!!.any { it.name.contains("illisible") })
    }
}
