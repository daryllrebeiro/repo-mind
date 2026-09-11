package dev.repomind.cli.lsp

import dev.repomind.core.index.IncrementalIndexer
import dev.repomind.core.rules.ArchitecturePreset
import dev.repomind.core.rules.RuleGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepoMindLspServerTest {

    private fun setupTestWorkspace(root: Path): Path {
        root.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.sample</groupId>
              <artifactId>lsp-test</artifactId>
              <version>1.0.0</version>
            </project>
            """.trimIndent(),
        )

        val srcWeb = root.resolve("src/main/java/com/sample/web")
        Files.createDirectories(srcWeb)
        val controllerFile = srcWeb.resolve("UserController.java")
        controllerFile.writeText(
            """
            package com.sample.web;
            import com.sample.repo.UserRepo;
            public class UserController {
                private UserRepo repo;
                public String getUser() {
                    return repo.findUser();
                }
            }
            """.trimIndent(),
        )

        val srcRepo = root.resolve("src/main/java/com/sample/repo")
        Files.createDirectories(srcRepo)
        val repoFile = srcRepo.resolve("UserRepo.java")
        repoFile.writeText(
            """
            package com.sample.repo;
            public class UserRepo {
                public String findUser() {
                    return "alice";
                }
            }
            """.trimIndent(),
        )

        // Generate rules.yaml enforcing no direct web-to-repo access
        val repomindDir = root.resolve(".repomind")
        Files.createDirectories(repomindDir)
        val rulesYaml = RuleGenerator.generateYaml(ArchitecturePreset.THREE_TIER, "com.sample")
        repomindDir.resolve("rules.yaml").writeText(rulesYaml)

        // Build index
        IncrementalIndexer(repomindDir.resolve("index.db")).update(root)

        return controllerFile
    }

    @Test
    fun `initialize returns LSP capabilities`(@TempDir tempDir: Path) {
        val server = RepoMindLspServer(tempDir)
        val initMsg = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val responses = server.handleMessage(initMsg)
        assertEquals(1, responses.size)

        val respObj = Json.parseToJsonElement(responses[0]).jsonObject
        val result = respObj["result"]?.jsonObject
        assertNotNull(result)
        val capabilities = result["capabilities"]?.jsonObject
        assertNotNull(capabilities)
        assertEquals(1, capabilities["textDocumentSync"]?.jsonPrimitive?.content?.toInt())
        assertTrue(capabilities["codeLensProvider"] != null)
        assertEquals(true, capabilities["hoverProvider"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `codeLens returns caller count and blast radius for class and methods`(@TempDir tempDir: Path) {
        val controllerFile = setupTestWorkspace(tempDir)
        val server = RepoMindLspServer(tempDir)

        val fileUri = controllerFile.toUri().toString()
        val codeLensMsg = """{"jsonrpc":"2.0","id":2,"method":"textDocument/codeLens","params":{"textDocument":{"uri":"$fileUri"}}}"""
        val responses = server.handleMessage(codeLensMsg)
        assertEquals(1, responses.size)

        val respObj = Json.parseToJsonElement(responses[0]).jsonObject
        val lenses = respObj["result"]?.jsonArray
        assertNotNull(lenses)
        assertTrue(lenses.isNotEmpty())

        val firstLens = lenses[0].jsonObject
        val command = firstLens["command"]?.jsonObject
        assertNotNull(command)
        val title = command["title"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(title.contains("callers") && title.contains("blast radius"), "Expected caller count in title, got: $title")
    }

    @Test
    fun `hover returns callers and blast radius markdown`(@TempDir tempDir: Path) {
        val controllerFile = setupTestWorkspace(tempDir)
        val server = RepoMindLspServer(tempDir)

        val fileUri = controllerFile.toUri().toString()
        val hoverMsg = """{"jsonrpc":"2.0","id":3,"method":"textDocument/hover","params":{"textDocument":{"uri":"$fileUri"},"position":{"line":3,"character":10}}}"""
        val responses = server.handleMessage(hoverMsg)
        assertEquals(1, responses.size)

        val respObj = Json.parseToJsonElement(responses[0]).jsonObject
        val hoverResult = respObj["result"]?.jsonObject
        assertNotNull(hoverResult)
        val contents = hoverResult["contents"]?.jsonObject
        assertNotNull(contents)
        val markdown = contents["value"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(markdown.contains("UserController"), "Hover must mention symbol name, got: $markdown")
        assertTrue(markdown.contains("Direct Callers"), "Hover must mention Direct Callers, got: $markdown")
    }

    @Test
    fun `didSave publishes architecture rule violation diagnostics`(@TempDir tempDir: Path) {
        val controllerFile = setupTestWorkspace(tempDir)
        val server = RepoMindLspServer(tempDir)

        val fileUri = controllerFile.toUri().toString()
        val didSaveMsg = """{"jsonrpc":"2.0","method":"textDocument/didSave","params":{"textDocument":{"uri":"$fileUri"}}}"""
        val responses = server.handleMessage(didSaveMsg)
        assertEquals(1, responses.size)

        val notification = Json.parseToJsonElement(responses[0]).jsonObject
        assertEquals("textDocument/publishDiagnostics", notification["method"]?.jsonPrimitive?.content)
        val params = notification["params"]?.jsonObject
        assertNotNull(params)
        assertEquals(fileUri, params["uri"]?.jsonPrimitive?.content)
        val diagnostics = params["diagnostics"]?.jsonArray
        assertNotNull(diagnostics)
        assertTrue(diagnostics.isNotEmpty(), "Expected architecture rule violation diagnostic on controller bypassing service")
        val diag = diagnostics[0].jsonObject
        assertEquals(1, diag["severity"]?.jsonPrimitive?.content?.toInt())
        assertTrue(diag["message"]?.jsonPrimitive?.content?.contains("three-tier") == true || diag["source"]?.jsonPrimitive?.content == "repomind")
    }
}
