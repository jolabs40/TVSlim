package net.jolabs40.tvslim.windows.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A tool the server exposes. [call] returns the text the model reads, or throws [ToolFailure]. */
class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,
    /** False for a tool that only reads local files, such as the catalogue. */
    val reachesTv: Boolean = true,
    val call: suspend (arguments: JsonObject) -> String,
)

/** A failure worth telling the model about: it comes back as a tool result with `isError`, not as a protocol error. */
class ToolFailure(message: String) : Exception(message)

/** Raised for malformed arguments; answered with JSON-RPC `-32602`. */
class InvalidArguments(message: String) : Exception(message)

/**
 * Model Context Protocol over newline-delimited JSON-RPC, tools only, every tool read-only.
 *
 * Both eras of the protocol are served from the same process. A legacy client opens with `initialize`
 * (2025-11-25 and earlier); a modern one (2026-07-28) puts its version in each request's `_meta` and may
 * probe with `server/discover`. Modern results carry `resultType` and the server's identity.
 */
class McpServer(
    private val name: String,
    private val version: String,
    private val instructions: String,
    tools: List<McpTool>,
    /** Called with each request's method and protocol era, for the trace file. */
    private val onRequest: (method: String, modern: Boolean) -> Unit = { _, _ -> },
) {
    // Deterministic order lets clients cache the list.
    private val tools = tools.sortedBy { it.name }
    private val toolsByName = tools.associateBy { it.name }

    /** Answers one line; null for a notification, which gets no answer. */
    suspend fun handle(line: String): String? {
        val message = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject
            ?: return error(JsonNull, PARSE_ERROR, "Parse error")
        val id = message["id"] ?: return null
        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
            ?: return error(id, INVALID_REQUEST, "Invalid request")
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

        val requested = ((params["_meta"] as? JsonObject)?.get(META_PROTOCOL_VERSION) as? JsonPrimitive)?.contentOrNull
        if (requested != null && requested != MODERN_VERSION) {
            return error(
                id,
                UNSUPPORTED_PROTOCOL_VERSION,
                "Unsupported protocol version",
                buildJsonObject {
                    putJsonArray("supported") {
                        add(MODERN_VERSION)
                        LEGACY_VERSIONS.forEach { add(it) }
                    }
                    put("requested", requested)
                },
            )
        }
        val modern = requested != null || method == "server/discover"
        onRequest(method, modern)

        val result = try {
            when (method) {
                "server/discover" -> discover()
                "initialize" -> initialize(params)
                "ping" -> JsonObject(emptyMap())
                "tools/list" -> listTools(modern)
                "tools/call" -> callTool(params)
                else -> return error(id, METHOD_NOT_FOUND, "Method not found: $method")
            }
        } catch (e: InvalidArguments) {
            return error(id, INVALID_PARAMS, e.message ?: "Invalid params")
        }
        return success(id, if (modern) result.asModern() else result)
    }

    /** The id of a request line, or null for a notification. */
    fun requestId(line: String): JsonElement? =
        (runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject)?.get("id")

    /** The id of the request a `notifications/cancelled` line cancels, or null for any other line. */
    fun cancelledRequest(line: String): JsonElement? {
        val message = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null
        if ((message["method"] as? JsonPrimitive)?.contentOrNull != "notifications/cancelled") return null
        return (message["params"] as? JsonObject)?.get("requestId")
    }

    private fun discover() = buildJsonObject {
        putJsonArray("supportedVersions") { add(MODERN_VERSION) }
        putJsonObject("capabilities") { putJsonObject("tools") {} }
        put("instructions", instructions)
        put("ttlMs", CACHE_MS)
        put("cacheScope", "public")
    }

    private fun initialize(params: JsonObject): JsonObject {
        val asked = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        return buildJsonObject {
            put("protocolVersion", asked?.takeIf { it in LEGACY_VERSIONS } ?: LEGACY_VERSIONS.first())
            putJsonObject("capabilities") { putJsonObject("tools") { put("listChanged", false) } }
            put("serverInfo", serverInfo())
            put("instructions", instructions)
        }
    }

    private fun listTools(modern: Boolean) = buildJsonObject {
        putJsonArray("tools") {
            tools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("title", tool.title)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                        putJsonObject("annotations") {
                            put("title", tool.title)
                            put("readOnlyHint", true)
                            put("destructiveHint", false)
                            put("idempotentHint", true)
                            put("openWorldHint", tool.reachesTv)
                        }
                    },
                )
            }
        }
        if (modern) {
            put("ttlMs", CACHE_MS)
            put("cacheScope", "public")
        }
    }

    private suspend fun callTool(params: JsonObject): JsonObject {
        val toolName = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: throw InvalidArguments("Missing tool name")
        val tool = toolsByName[toolName] ?: throw InvalidArguments("Unknown tool: $toolName")
        val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        val (text, failed) = try {
            tool.call(arguments) to false
        } catch (e: ToolFailure) {
            (e.message ?: "Failed") to true
        }
        return buildJsonObject {
            putJsonArray("content") {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                )
            }
            put("isError", failed)
        }
    }

    private fun serverInfo() = buildJsonObject {
        put("name", name)
        put("title", "TV Slim")
        put("version", version)
    }

    private fun JsonObject.asModern() = JsonObject(
        this + mapOf(
            "resultType" to JsonPrimitive("complete"),
            "_meta" to buildJsonObject { put(META_SERVER_INFO, serverInfo()) },
        ),
    )

    private fun success(id: JsonElement, result: JsonObject) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun error(id: JsonElement, code: Int, message: String, data: JsonElement? = null) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
            if (data != null) put("data", data)
        }
    }.toString()

    companion object {
        const val MODERN_VERSION = "2026-07-28"

        /** Newest first: an `initialize` asking for another version gets the newest. */
        val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

        private const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
        private const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

        /** The tool list only changes with the application. */
        private const val CACHE_MS = 3_600_000

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val UNSUPPORTED_PROTOCOL_VERSION = -32022
    }
}

/** A JSON Schema for an object whose properties are all strings. */
fun stringArguments(vararg properties: Pair<String, String>, required: List<String> = emptyList()) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        properties.forEach { (property, description) ->
            putJsonObject(property) {
                put("type", "string")
                put("description", description)
            }
        }
    }
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
    put("additionalProperties", false)
}
