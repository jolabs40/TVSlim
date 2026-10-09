package net.jolabs40.tvslim.windows.update

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

/** Downloads against a real local HTTP server: what lands, and what never does. */
class GithubClientTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/repos/jolabs40/TVSlim/releases") {
            respond(it, 200, """[{"tag_name":"windows-v1.0.1","assets":[]}]""".toByteArray())
        }
        server.createContext("/fichier") { respond(it, 200, ByteArray(300_000) { i -> (i % 251).toByte() }) }
        server.createContext("/absent") { respond(it, 404, ByteArray(0)) }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun client() = GithubClient(repository = "jolabs40/TVSlim", appVersion = "test", api = base)

    @Test
    fun `releases are read from the API`() = runTest {
        assertEquals("windows-v1.0.1", client().releases().single().tag)
    }

    @Test
    fun `a complete download lands under its name, with no temporary file`() = runTest {
        val target = File(folder.root, "a.msi")
        var last = 0f

        client().download("$base/fichier", target, maxSize = 1_000_000) { last = it }

        assertEquals(300_000L, target.length())
        assertEquals(1f, last)
        assertFalse(File(folder.root, "a.msi.part").exists())
    }

    @Test
    fun `a file larger than expected is dropped without leaving anything`() = runTest {
        val target = File(folder.root, "a.msi")

        val error = runCatching { client().download("$base/fichier", target, maxSize = 1_000) {} }

        assertTrue(error.exceptionOrNull() is IOException)
        assertFalse(target.exists())
        assertFalse(File(folder.root, "a.msi.part").exists())
    }

    @Test
    fun `an error response is not mistaken for a file`() = runTest {
        val target = File(folder.root, "a.msi")

        val error = runCatching { client().download("$base/absent", target, maxSize = 1_000) {} }

        assertTrue(error.exceptionOrNull() is IOException)
        assertFalse(target.exists())
    }
}
