package dev.repomind.core.index

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/**
 * Client for communicating with a running RepoMind daemon via local IPC.
 */
class DaemonClient(private val repomindDir: Path) {

    private val portFile = repomindDir.resolve("daemon.port")

    fun readPort(): Int? {
        if (!Files.isRegularFile(portFile)) return null
        return try {
            Files.readString(portFile).trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun isAvailable(): Boolean {
        val port = readPort() ?: return false
        return try {
            val response = sendRequest(buildJsonObject { put("action", "ping") }.toString(), port, timeoutMs = 500)
            response != null && (Json.parseToJsonElement(response) as? JsonObject)?.get("status")?.jsonPrimitive?.content == "ok"
        } catch (_: Exception) {
            false
        }
    }

    fun queryCallers(symbol: String): List<String>? {
        val port = readPort() ?: return null
        return try {
            val req = buildJsonObject {
                put("action", "callers")
                put("symbol", symbol)
            }.toString()
            val raw = sendRequest(req, port, timeoutMs = 2000) ?: return null
            val json = Json.parseToJsonElement(raw) as? JsonObject
            if (json?.get("status")?.jsonPrimitive?.content == "ok") {
                json["callers"]?.jsonArray?.map { it.jsonPrimitive.content }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun sendRequest(jsonPayload: String, port: Int, timeoutMs: Int = 1000): String? {
        return Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
            sock.soTimeout = timeoutMs
            val writer = PrintWriter(sock.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            writer.println(jsonPayload)
            reader.readLine()
        }
    }
}
