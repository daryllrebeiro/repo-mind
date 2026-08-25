package dev.repomind.mcp

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpDispatcherTest {

    private val dispatcher = McpDispatcher()

    private fun resultPayload(responseLine: String): String {
        val root = Json.parseToJsonElement(responseLine).jsonObject
        val result = root["result"]!!.jsonObject
        return result["content"]!!.toString().let { content ->
            val inner = Json.parseToJsonElement(content).let { arr ->
                arr.toString().substringAfter("\"text\":\"").substringBefore("\",\"")
            }
            inner.replace("\\\"", "\"").replace("\\\\", "\\")
        }
    }

    @Test
    fun `initialize returns protocol version and server info`() {
        val response = dispatcher.handle("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")!!

        assertTrue(response.contains("\"protocolVersion\""))
        assertTrue(response.contains("repomind"))
    }

    @Test
    fun `notifications produce no response`() {
        assertEquals(null, dispatcher.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `tools list exposes the four query tools`() {
        val response = dispatcher.handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")!!

        for (tool in listOf("find_symbol", "find_callers", "find_related_tests", "analyze_change_impact")) {
            assertTrue(response.contains(tool), "missing tool $tool")
        }
    }

    @Test
    fun `unknown method yields json rpc error`() {
        val response = dispatcher.handle("""{"jsonrpc":"2.0","id":3,"method":"bogus"}""")!!

        assertTrue(response.contains("-32601"))
    }

    @Test
    fun `malformed line yields parse error`() {
        val response = dispatcher.handle("this is not json")

        assertTrue(response!!.contains("-32700"))
    }

    private fun buildIndexedRepo(): Path {
        val root = Files.createTempDirectory("repomind-mcp")
        val src = root.resolve("src/main/java")
        val testSrc = root.resolve("src/test/java")
        src.resolve("com/example/MailConfig.java").toFile().let { f -> f.parentFile.mkdirs(); f.writeText("package com.example;\npublic class MailConfig {\n    public void send() {}\n}") }
        testSrc.resolve("com/example/MailConfigTest.java").toFile().let { f -> f.parentFile.mkdirs(); f.writeText("package com.example;\nimport com.example.MailConfig;\nclass MailConfigTest {\n    void t() { new MailConfig().send(); }\n}") }

        val module = RepoModule(
            name = "m",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(src, isTest = false), SourceRoot(testSrc, isTest = true)),
        )
        val parsed = JavaSemanticParser().parseModule(module, emptyList())
        SymbolDatabase.open(root.resolve(".repomind/index.db")).use { db ->
            db.replaceModule(module.name, parsed)
            db.edges.replaceModule(module.name, parsed.edges)
        }
        return root
    }

    @Test
    fun `tools call executes find_callers and impact against real index`() {
        val repo = buildIndexedRepo().toString().replace('\\', '/')
        val callersResponse = dispatcher.handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"find_callers","arguments":{"repoPath":"$repo","symbol":"com.example.MailConfig"}}}""",
        )!!
        assertTrue(callersResponse.contains("MailConfigTest") || callersResponse.contains("com.example"), callersResponse)

        val impactResponse = dispatcher.handle(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"analyze_change_impact","arguments":{"repoPath":"$repo","symbol":"com.example.MailConfig"}}}""",
        )!!
        assertTrue(impactResponse.contains("score"), impactResponse)
        assertTrue(impactResponse.contains("level"), impactResponse)
    }

    @Test
    fun `tools call with missing repo reports error without crashing`() {
        val response = dispatcher.handle(
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"find_symbol","arguments":{"prefix":"X"}}}""",
        )!!

        assertTrue(response.contains("isError\\\":true") || response.contains("error"))
    }
}
