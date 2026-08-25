package dev.repomind.mcp

import dev.repomind.core.query.CappedCallers
import dev.repomind.core.query.CappedSymbols
import dev.repomind.core.query.CappedTests
import dev.repomind.core.query.QueryEngineException
import dev.repomind.core.query.RepoQueryEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String? = null,
    val id: kotlinx.serialization.json.JsonElement? = null,
    val method: String,
    val params: JsonObject? = null,
)

class McpDispatcher(private val serverName: String = "repomind", private val serverVersion: String = "0.1.0") {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun handle(line: String): String? {
        if (line.isBlank()) return null
        val request = try {
            json.decodeFromString(JsonRpcRequest.serializer(), line)
        } catch (_: Exception) {
            return errorResponse(null, -32700, "parse error")
        }
        return when (request.method) {
            "initialize" -> okResponse(request.id, initializeResult())
            "notifications/initialized", "initialized" -> null
            "ping" -> okResponse(request.id, buildJsonObject { })
            "tools/list" -> okResponse(request.id, toolsList())
            "tools/call" -> okResponse(request.id, toolCall(request.params))
            else -> {
                if (request.id == null) null else errorResponse(request.id, -32601, "method not found: ${request.method}")
            }
        }
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", "2024-11-05")
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject { })
        })
        put("serverInfo", buildJsonObject {
            put("name", serverName)
            put("version", serverVersion)
        })
    }

    private fun toolsList(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            add(toolDef(
                name = "find_symbol",
                description = "Search indexed symbols by name prefix. Returns qualified names, kinds, file locations.",
                props = mapOf(
                    "repoPath" to "Absolute path to the analyzed repository root",
                    "prefix" to "Symbol name prefix to search for",
                    "limit" to "(optional) max results, default 20",
                ),
                required = listOf("repoPath", "prefix"),
            ))
            add(toolDef(
                name = "find_callers",
                description = "Direct and transitive callers of a symbol from the code graph.",
                props = mapOf(
                    "repoPath" to "Absolute path to the analyzed repository root",
                    "symbol" to "Fully-qualified symbol name (type or type#method)",
                    "limit" to "(optional) max results, default 20",
                ),
                required = listOf("repoPath", "symbol"),
            ))
            add(toolDef(
                name = "find_related_tests",
                description = "Tests that exercise the given production symbol directly.",
                props = mapOf(
                    "repoPath" to "Absolute path to the analyzed repository root",
                    "symbol" to "Fully-qualified production symbol name",
                ),
                required = listOf("repoPath", "symbol"),
            ))
            add(toolDef(
                name = "analyze_change_impact",
                description = "Deterministic impact analysis of changing a symbol: risk score 0-100 with level, evidence-traceable signals, caller/test reachability.",
                props = mapOf(
                    "repoPath" to "Absolute path to the analyzed repository root",
                    "symbol" to "Fully-qualified symbol name being changed",
                ),
                required = listOf("repoPath", "symbol"),
            ))
        })
    }

    private fun toolDef(name: String, description: String, props: Map<String, String>, required: List<String>): JsonObject =
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    for ((propName, propDesc) in props) {
                        put(propName, buildJsonObject { put("type", "string"); put("description", propDesc) })
                    }
                })
                put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }

    internal fun toolCall(params: JsonObject?): JsonObject {
        if (params == null) return textResult("""{"error":"missing params"}""", isError = true)
        val name = params["name"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return textResult("""{"error":"missing tool name"}""", isError = true)
        val args = params["arguments"]?.jsonObject ?: buildJsonObject { }

        fun arg(key: String): String {
            val value = (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (value == null) {
                throw QueryEngineException("missing argument '$key'")
            }
            return value
        }

        return try {
            val repoPath = Path.of(arg("repoPath"))
            RepoQueryEngine(repoPath.resolve(".repomind/index.db")).use { engine ->
                val payload = when (name) {
                    "find_symbol" -> {
                        val limit = (args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 20
                        json.encodeToString(CappedSymbols.serializer(), engine.findSymbol(arg("prefix"), limit))
                    }
                    "find_callers" -> {
                        val limit = (args["limit"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 20
                        json.encodeToString(CappedCallers.serializer(), engine.findCallers(arg("symbol"), limit))
                    }
                    "find_related_tests" ->
                        json.encodeToString(CappedTests.serializer(), engine.findRelatedTests(arg("symbol")))
                    "analyze_change_impact" ->
                        json.encodeToString(dev.repomind.core.impact.ImpactReport.serializer(), engine.impact(arg("symbol")))
                    else -> return textResult("""{"error":"unknown tool: $name"}""", isError = true)
                }
                textResult(payload, isError = false)
            }
        } catch (e: Exception) {
            textResult("""{"error":${JsonPrimitiveEscaper.escape(e.message ?: e.javaClass.simpleName)}}""", isError = true)
        }
    }

    private fun textResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", isError)
    }

    private fun okResponse(id: kotlinx.serialization.json.JsonElement?, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: kotlinx.serialization.json.JsonNull)
            put("result", result)
        }.toString()

    private fun errorResponse(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: kotlinx.serialization.json.JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }.toString()
}

internal object JsonPrimitiveEscaper {
    fun escape(raw: String): String =
        buildString {
            append('"')
            for (c in raw) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
}
