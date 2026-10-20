package net.jolabs40.tvslim.windows.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class McpServerTest {

    private val server = McpServer(
        name = "tvslim",
        version = "1.7.0",
        instructions = "Read-only.",
        tools = listOf(
            McpTool("zeta", "Zeta", "Last in order.", stringArguments()) { "z" },
            McpTool("alpha", "Alpha", "Echoes its argument.", stringArguments("text" to "Any text."), reachesTv = false) {
                it["text"]?.jsonPrimitive?.content ?: throw ToolFailure("No text")
            },
        ),
    )

    private suspend fun ask(line: String): JsonObject = Json.parseToJsonElement(server.handle(line)!!).jsonObject

    private fun modernMeta(version: String = McpServer.MODERN_VERSION) =
        """"_meta":{"io.modelcontextprotocol/protocolVersion":"$version","io.modelcontextprotocol/clientCapabilities":{}}"""

    @Test
    fun `a legacy client gets the version it asked for`() = runTest {
        val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{}}}""")

        val result = answer["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("tvslim", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(result["resultType"])
    }

    @Test
    fun `an unknown legacy version falls back to the newest legacy one`() = runTest {
        val answer = ask("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2023-01-01"}}""")

        assertEquals(McpServer.LEGACY_VERSIONS.first(), answer["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the stdio probe identifies a modern server`() = runTest {
        val answer = ask("""{"jsonrpc":"2.0","id":"d","method":"server/discover","params":{${modernMeta()}}}""")

        val result = answer["result"]!!.jsonObject
        assertEquals("complete", result["resultType"]!!.jsonPrimitive.content)
        assertEquals(listOf(McpServer.MODERN_VERSION), result["supportedVersions"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue("tools" in result["capabilities"]!!.jsonObject)
        val info = result["_meta"]!!.jsonObject["io.modelcontextprotocol/serverInfo"]!!.jsonObject
        assertEquals("1.7.0", info["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unsupported modern version is refused with the supported list`() = runTest {
        val answer = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{${modernMeta("2099-01-01")}}}""")

        val error = answer["error"]!!.jsonObject
        assertEquals(-32022, error["code"]!!.jsonPrimitive.int)
        assertEquals("2099-01-01", error["data"]!!.jsonObject["requested"]!!.jsonPrimitive.content)
        assertTrue(error["data"]!!.jsonObject["supported"]!!.jsonArray.any { it.jsonPrimitive.content == McpServer.MODERN_VERSION })
    }

    @Test
    fun `tools are listed in name order, read-only, with their cache hints when modern`() = runTest {
        val answer = ask("""{"jsonrpc":"2.0","id":3,"method":"tools/list","params":{${modernMeta()}}}""")

        val result = answer["result"]!!.jsonObject
        val tools = result["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("alpha", "zeta"), tools.map { it["name"]!!.jsonPrimitive.content })
        val annotations = tools.first()["annotations"]!!.jsonObject
        assertTrue(annotations["readOnlyHint"]!!.jsonPrimitive.boolean)
        assertFalse(annotations["destructiveHint"]!!.jsonPrimitive.boolean)
        assertFalse(annotations["openWorldHint"]!!.jsonPrimitive.boolean)
        assertTrue("ttlMs" in result)
        assertEquals("public", result["cacheScope"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a legacy tool list carries no modern fields`() = runTest {
        val result = ask("""{"jsonrpc":"2.0","id":3,"method":"tools/list"}""")["result"]!!.jsonObject

        assertNull(result["resultType"])
        assertNull(result["ttlMs"])
    }

    @Test
    fun `a tool answers with text, and a tool failure is a result the model reads`() = runTest {
        val ok = ask("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"alpha","arguments":{"text":"hello"}}}""")
        val failed = ask("""{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"alpha","arguments":{}}}""")

        val okResult = ok["result"]!!.jsonObject
        assertEquals("hello", okResult["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertFalse(okResult["isError"]!!.jsonPrimitive.boolean)
        val failedResult = failed["result"]!!.jsonObject
        assertEquals("No text", failedResult["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(failedResult["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an unknown tool or method is a protocol error`() = runTest {
        val tool = ask("""{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"nope"}}""")
        val method = ask("""{"jsonrpc":"2.0","id":7,"method":"resources/list"}""")

        assertEquals(-32602, tool["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, method["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `notifications get no answer, garbage gets a parse error`() = runTest {
        assertNull(server.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(-32700, ask("not json")["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `over stdio, each answer is one line, and the loop ends when input closes`() = runTest {
        val input = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"alpha","arguments":{"text":"two\nlines"}}}""",
        ).joinToString("\n", postfix = "\n")
        val output = ByteArrayOutputStream()

        serveStdio(server, ByteArrayInputStream(input.toByteArray()), output)

        val lines = output.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        val texts = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(setOf("1", "2"), texts.map { it["id"]!!.jsonPrimitive.content }.toSet())
    }
}
