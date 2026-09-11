package dev.repomind.core.index

import dev.repomind.storage.sqlite.SymbolDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class DaemonRequest(
    val action: String,
    val symbol: String? = null,
)

@Serializable
data class DaemonResponse(
    val status: String,
    val message: String? = null,
    val port: Int = 0,
    val callers: List<String> = emptyList(),
)

/**
 * Local loopback IPC server exposing high-speed index queries and daemon status
 * to the CLI and MCP server without JVM restart latency.
 */
class DaemonServer(
    private val repomindDir: Path,
    private val dbPath: Path,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val portFile = repomindDir.resolve("daemon.port")
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "repomind-daemon-worker").apply { isDaemon = true }
    }

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        Files.createDirectories(repomindDir)
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        val allocatedPort = ss.localPort
        Files.writeString(portFile, allocatedPort.toString())
        running.set(true)

        executor.execute {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    executor.execute { handleClient(client) }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val writer = PrintWriter(sock.getOutputStream(), true)

            val line = reader.readLine() ?: return
            val response = try {
                val json = Json.parseToJsonElement(line) as? JsonObject
                val action = json?.get("action")?.jsonPrimitive?.content ?: "unknown"
                val symbol = json?.get("symbol")?.jsonPrimitive?.content

                when (action) {
                    "ping" -> buildJsonObject {
                        put("status", "ok")
                        put("message", "pong")
                        put("port", port)
                    }.toString()

                    "status" -> {
                        val report = if (Files.isRegularFile(dbPath)) {
                            SymbolDatabase.open(dbPath).use { db ->
                                val rep = db.confidenceReport()
                                "totalSymbols=${rep.totalSymbols},totalEdges=${rep.totalEdges}"
                            }
                        } else "not_indexed"
                        buildJsonObject {
                            put("status", "ok")
                            put("message", report)
                            put("port", port)
                        }.toString()
                    }

                    "callers" -> {
                        if (symbol.isNullOrBlank()) {
                            buildJsonObject {
                                put("status", "error")
                                put("message", "missing symbol parameter")
                            }.toString()
                        } else {
                            val callers = if (Files.isRegularFile(dbPath)) {
                                SymbolDatabase.open(dbPath).use { db ->
                                    db.edges.transitiveCallers(symbol).toList().sorted()
                                }
                            } else emptyList()
                            Json.encodeToString(
                                DaemonResponse.serializer(),
                                DaemonResponse(status = "ok", callers = callers, port = port),
                            )
                        }
                    }

                    "stop" -> {
                        executor.execute { stop() }
                        buildJsonObject {
                            put("status", "ok")
                            put("message", "stopping")
                        }.toString()
                    }

                    else -> buildJsonObject {
                        put("status", "error")
                        put("message", "unknown action: $action")
                    }.toString()
                }
            } catch (e: Exception) {
                buildJsonObject {
                    put("status", "error")
                    put("message", e.message ?: "internal daemon error")
                }.toString()
            }

            writer.println(response)
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            try {
                serverSocket?.close()
            } catch (_: Exception) {
            }
            try {
                Files.deleteIfExists(portFile)
            } catch (_: Exception) {
            }
            executor.shutdownNow()
        }
    }

    override fun close() {
        stop()
    }
}
