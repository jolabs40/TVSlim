package net.jolabs40.tvslim.windows.maj

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/** Le téléchargement sur un vrai serveur HTTP local : ce qui arrive, et ce qui n'arrive jamais. */
class ClientGithubTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private lateinit var serveur: HttpServer
    private val base get() = "http://127.0.0.1:${serveur.address.port}"

    @Before
    fun demarrer() {
        serveur = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        serveur.createContext("/repos/jolabs40/TVSlim/releases") {
            repondre(it, 200, """[{"tag_name":"windows-v1.0.1","assets":[]}]""".toByteArray())
        }
        serveur.createContext("/fichier") { repondre(it, 200, ByteArray(300_000) { i -> (i % 251).toByte() }) }
        serveur.createContext("/absent") { repondre(it, 404, ByteArray(0)) }
        serveur.start()
    }

    @After
    fun arreter() = serveur.stop(0)

    private fun repondre(echange: HttpExchange, code: Int, corps: ByteArray) {
        echange.sendResponseHeaders(code, if (corps.isEmpty()) -1 else corps.size.toLong())
        echange.responseBody.use { it.write(corps) }
    }

    private fun client() = ClientGithub(depot = "jolabs40/TVSlim", versionApp = "test", api = base)

    @Test
    fun `les publications se lisent depuis l'API`() = runTest {
        assertEquals("windows-v1.0.1", client().publications().single().tag)
    }

    @Test
    fun `un telechargement complet arrive sous son nom, sans fichier provisoire`() = runTest {
        val cible = File(dossier.root, "a.msi")
        var derniere = 0f

        client().telecharger("$base/fichier", cible, tailleMax = 1_000_000) { derniere = it }

        assertEquals(300_000L, cible.length())
        assertEquals(1f, derniere)
        assertFalse(File(dossier.root, "a.msi.part").exists())
    }

    @Test
    fun `un fichier plus gros que prevu est abandonne sans rien laisser`() = runTest {
        val cible = File(dossier.root, "a.msi")

        val erreur = runCatching { client().telecharger("$base/fichier", cible, tailleMax = 1_000) {} }

        assertTrue(erreur.exceptionOrNull() is IOException)
        assertFalse(cible.exists())
        assertFalse(File(dossier.root, "a.msi.part").exists())
    }

    @Test
    fun `une reponse en erreur n'est pas prise pour un fichier`() = runTest {
        val cible = File(dossier.root, "a.msi")

        val erreur = runCatching { client().telecharger("$base/absent", cible, tailleMax = 1_000) {} }

        assertTrue(erreur.exceptionOrNull() is IOException)
        assertFalse(cible.exists())
    }
}
