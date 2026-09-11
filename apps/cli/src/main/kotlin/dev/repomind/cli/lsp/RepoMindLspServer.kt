package dev.repomind.cli.lsp

import dev.repomind.core.impact.ImpactAnalyzer
import dev.repomind.core.index.IncrementalIndexer
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.rules.RuleEvaluator
import dev.repomind.core.rules.RuleLoader
import dev.repomind.core.rules.TypeStereotypeInfo
import dev.repomind.storage.sqlite.SymbolDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class LspRpcMessage(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String? = null,
    val params: JsonObject? = null,
)

class RepoMindLspServer(val repoRoot: Path) {

    private val logger = LoggerFactory.getLogger(RepoMindLspServer::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val running = AtomicBoolean(true)

    private val dbPath: Path get() = repoRoot.resolve(".repomind/index.db")
    private val rulesPath: Path get() = repoRoot.resolve(".repomind/rules.yaml")

    /**
     * Processes a single JSON-RPC message payload and returns any response or notification strings.
     */
    fun handleMessage(rawPayload: String): List<String> {
        if (rawPayload.isBlank()) return emptyList()
        val request = try {
            json.decodeFromString(LspRpcMessage.serializer(), rawPayload)
        } catch (e: Exception) {
            logger.warn("Failed to parse LSP JSON-RPC message: {}", e.message)
            return listOf(errorResponse(null, -32700, "Parse error"))
        }

        val method = request.method ?: return emptyList()
        val id = request.id
        val results = mutableListOf<String>()

        when (method) {
            "initialize" -> {
                results.add(okResponse(id, buildJsonObject {
                    put("capabilities", buildJsonObject {
                        put("textDocumentSync", 1) // Full sync
                        put("codeLensProvider", buildJsonObject {
                            put("resolveProvider", false)
                        })
                        put("hoverProvider", true)
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", "repomind-lsp")
                        put("version", "0.1.0")
                    })
                }))
            }
            "initialized" -> {
                // Client confirmed initialization
            }
            "textDocument/didOpen" -> {
                val uriStr = request.params?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.content
                if (uriStr != null) {
                    val diagnostics = computeDiagnosticsForFile(uriStr)
                    results.add(notification("textDocument/publishDiagnostics", buildJsonObject {
                        put("uri", uriStr)
                        put("diagnostics", diagnostics)
                    }))
                }
            }
            "textDocument/didSave" -> {
                val uriStr = request.params?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.content
                if (uriStr != null) {
                    reindexFileIfPossible(uriStr)
                    val diagnostics = computeDiagnosticsForFile(uriStr)
                    results.add(notification("textDocument/publishDiagnostics", buildJsonObject {
                        put("uri", uriStr)
                        put("diagnostics", diagnostics)
                    }))
                }
            }
            "textDocument/codeLens" -> {
                val uriStr = request.params?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.content
                val lenses = if (uriStr != null) computeCodeLenses(uriStr) else buildJsonArray { }
                results.add(okResponse(id, lenses))
            }
            "textDocument/hover" -> {
                val uriStr = request.params?.get("textDocument")?.jsonObject?.get("uri")?.jsonPrimitive?.content
                val pos = request.params?.get("position")?.jsonObject
                val line0 = pos?.get("line")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val hover = if (uriStr != null) computeHover(uriStr, line0 + 1) else null
                results.add(okResponse(id, hover ?: buildJsonObject { }))
            }
            "shutdown" -> {
                results.add(okResponse(id, buildJsonObject { }))
            }
            "exit" -> {
                running.set(false)
            }
            else -> {
                if (id != null) {
                    results.add(errorResponse(id, -32601, "Method not found: $method"))
                }
            }
        }

        return results
    }

    fun startStdio(input: InputStream = System.`in`, output: OutputStream = System.out) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        while (running.get()) {
            val payload = readHeaderAndPayload(reader) ?: break
            val responses = handleMessage(payload)
            for (resp in responses) {
                writeMessage(output, resp)
            }
        }
    }

    fun startSocket(port: Int) {
        val serverSocket = ServerSocket(port)
        System.err.println("RepoMind LSP server listening on port $port")
        val socket = serverSocket.accept()
        socket.use { s ->
            val input = s.getInputStream()
            val output = s.getOutputStream()
            startStdio(input, output)
        }
    }

    fun stop() {
        running.set(false)
    }

    private fun reindexFileIfPossible(uriStr: String) {
        if (!Files.isRegularFile(dbPath)) return
        try {
            val filePath = uriToPath(uriStr)
            if (Files.isRegularFile(filePath)) {
                IncrementalIndexer(dbPath).update(repoRoot)
            }
        } catch (e: Exception) {
            logger.warn("Incremental reindex on save failed: {}", e.message)
        }
    }

    private fun computeDiagnosticsForFile(uriStr: String): JsonArray {
        if (!Files.isRegularFile(dbPath) || !Files.isRegularFile(rulesPath)) {
            return buildJsonArray { }
        }

        return try {
            val rules = RuleLoader.load(rulesPath)
            if (rules.isEmpty()) return buildJsonArray { }

            val filePath = uriToPath(uriStr)
            val normalizedPath = filePath.toAbsolutePath().normalize().toString().replace('\\', '/')

            SymbolDatabase.open(dbPath, readOnly = true).use { db ->
                val types = db.allTypes().map { TypeStereotypeInfo(it.qualifiedName, it.annotations) }
                val edges = db.edges.findAll().map { row ->
                    DependencyEdge(row.sourceFqn, row.targetFqn, EdgeKind.valueOf(row.kind), Confidence.valueOf(row.confidence), row.line)
                }

                val report = RuleEvaluator().evaluate(
                    rules = rules,
                    types = types,
                    edges = edges,
                    failOnViolation = false,
                    checkCycles = false,
                )

                // Match violations to this file by sourceFqn
                val fileSymbols = db.findByFilePath(normalizedPath).map { it.qualifiedName }.toSet()
                val matchedViolations = report.violations.filter { v ->
                    val srcOwner = v.sourceFqn.substringBefore('#')
                    fileSymbols.contains(srcOwner) || fileSymbols.contains(v.sourceFqn)
                }

                buildJsonArray {
                    for (v in matchedViolations) {
                        val line0 = if (v.line > 0) v.line - 1 else 0
                        add(buildJsonObject {
                            put("range", buildJsonObject {
                                put("start", buildJsonObject { put("line", line0); put("character", 0) })
                                put("end", buildJsonObject { put("line", line0); put("character", 120) })
                            })
                            put("severity", 1) // Error
                            put("source", "repomind")
                            put("code", v.rule)
                            put("message", "[${v.rule}] ${v.message ?: "Architecture violation: ${v.sourceFqn} -> ${v.targetFqn}"}")
                        })
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Diagnostics evaluation error: {}", e.message)
            buildJsonArray { }
        }
    }

    private fun computeCodeLenses(uriStr: String): JsonArray {
        if (!Files.isRegularFile(dbPath)) return buildJsonArray { }
        val filePath = uriToPath(uriStr)
        val normalizedPath = filePath.toAbsolutePath().normalize().toString().replace('\\', '/')

        return try {
            SymbolDatabase.open(dbPath, readOnly = true).use { db ->
                val symbols = db.findByFilePath(normalizedPath)
                    .filter { it.kind in setOf("CLASS", "INTERFACE", "ENUM", "RECORD", "METHOD") }

                buildJsonArray {
                    for (sym in symbols) {
                        val callers = db.edges.findDirectCallers(sym.qualifiedName).size
                        val blastRadius = db.edges.transitiveCallers(sym.qualifiedName, 3).size
                        val line0 = (sym.lineStart - 1).coerceAtLeast(0)

                        val title = "$callers callers | blast radius $blastRadius"
                        add(buildJsonObject {
                            put("range", buildJsonObject {
                                put("start", buildJsonObject { put("line", line0); put("character", 0) })
                                put("end", buildJsonObject { put("line", line0); put("character", 0) })
                            })
                            put("command", buildJsonObject {
                                put("title", title)
                                put("command", "repomind.showCallers")
                                put("arguments", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(sym.qualifiedName)) })
                            })
                        })
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("CodeLens generation error: {}", e.message)
            buildJsonArray { }
        }
    }

    private fun computeHover(uriStr: String, line1: Int): JsonObject? {
        if (!Files.isRegularFile(dbPath)) return null
        val filePath = uriToPath(uriStr)
        val normalizedPath = filePath.toAbsolutePath().normalize().toString().replace('\\', '/')

        return try {
            SymbolDatabase.open(dbPath, readOnly = true).use { db ->
                val symbols = db.findByFilePath(normalizedPath)
                val targetSym = symbols.firstOrNull { it.lineStart <= line1 && line1 <= it.lineEnd }
                    ?: symbols.minByOrNull { Math.abs(it.lineStart - line1) }
                    ?: return null

                val directCallers = db.edges.findDirectCallers(targetSym.qualifiedName)
                val directCallees = db.edges.findDirectCallees(targetSym.qualifiedName)
                val blastRadius = db.edges.transitiveCallers(targetSym.qualifiedName, 3)

                val markdown = buildString {
                    appendLine("### `${targetSym.qualifiedName}` (${targetSym.kind})")
                    appendLine("- **Direct Callers**: ${directCallers.size}")
                    appendLine("- **Direct Callees**: ${directCallees.size}")
                    appendLine("- **Blast Radius**: ${blastRadius.size} downstream components")
                    appendLine("- **Module**: `${targetSym.module}`")
                    if (directCallers.isNotEmpty()) {
                        appendLine("\n**Sample Callers:**")
                        directCallers.take(3).forEach { appendLine("- `${it.sourceFqn}` [${it.confidence}]") }
                    }
                }

                buildJsonObject {
                    put("contents", buildJsonObject {
                        put("kind", "markdown")
                        put("value", markdown.trim())
                    })
                }
            }
        } catch (e: Exception) {
            logger.warn("Hover generation error: {}", e.message)
            null
        }
    }

    private fun uriToPath(uriStr: String): Path {
        return try {
            Path.of(URI(uriStr))
        } catch (_: Exception) {
            val clean = uriStr.removePrefix("file://").removePrefix("file:")
            Path.of(clean)
        }
    }

    private fun readHeaderAndPayload(reader: BufferedReader): String? {
        var contentLength = -1
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty() || line == "\r") {
                // End of headers
                break
            }
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
            } else if (line.startsWith("{")) {
                // Bare JSON line without headers
                return line
            }
        }

        if (contentLength <= 0) return null
        val buffer = CharArray(contentLength)
        var readTotal = 0
        while (readTotal < contentLength) {
            val count = reader.read(buffer, readTotal, contentLength - readTotal)
            if (count < 0) break
            readTotal += count
        }
        return String(buffer, 0, readTotal)
    }

    private fun writeMessage(output: OutputStream, jsonContent: String) {
        val bytes = jsonContent.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun okResponse(id: JsonElement?, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id) else put("id", kotlinx.serialization.json.JsonPrimitive(1))
        put("result", result)
    }.toString()

    private fun errorResponse(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        if (id != null) put("id", id) else put("id", kotlinx.serialization.json.JsonPrimitive(1))
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    private fun notification(method: String, params: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", method)
        put("params", params)
    }.toString()
}
