package dev.repomind.core.report

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportGeneratorTest {

    private fun buildIndexedRepo(): Path {
        val root = Files.createTempDirectory("repomind-report")
        val src = root.resolve("src/main/java")
        src.resolve("com/web/OrderController.java").toFile().let { f ->
            f.parentFile.mkdirs()
            f.writeText("package com.web;\nimport com.app.OrderService;\nimport org.springframework.web.bind.annotation.RestController;\n\n@RestController\npublic class OrderController {\n    private final OrderService service;\n    public OrderController(OrderService s) { this.service = s; }\n    public void place() { service.place(); }\n}")
        }
        src.resolve("com/app/OrderService.java").toFile().let { f ->
            f.parentFile.mkdirs()
            f.writeText("package com.app;\nimport com.data.OrderRepository;\nimport org.springframework.stereotype.Service;\n\n@Service\npublic class OrderService {\n    private final OrderRepository repo;\n    public OrderService(OrderRepository r) { this.repo = r; }\n    public void place() { repo.save(); }\n}")
        }
        src.resolve("com/data/OrderRepository.java").toFile().let { f ->
            f.parentFile.mkdirs()
            f.writeText("package com.data;\nimport org.springframework.stereotype.Repository;\n\n@Repository\npublic class OrderRepository {\n    public void save() {}\n}")
        }

        val module = RepoModule(name = "app", path = root, buildFile = null, sourceRoots = listOf(SourceRoot(src, isTest = false)))
        val parsed = JavaSemanticParser().parseModule(module, emptyList())
        SymbolDatabase.open(root.resolve(".repomind/index.db")).use { db ->
            db.replaceModule(module.name, parsed)
            db.edges.replaceModule(module.name, parsed.edges)
        }
        return root
    }

    @Test
    fun `report contains all sections with diagrams and hotspot evidence`() {
        val root = buildIndexedRepo()
        val markdown = ReportGenerator(root.resolve(".repomind/index.db"), "order-app").generate()

        for (section in listOf("# Analysis: order-app", "## 1. Overview", "## 2. Architecture", "## 3. Key Flows", "## 4. Hotspots", "## 5. Points of Improvement", "## 6. Code Smells", "## 7. Appendix")) {
            assertTrue(markdown.contains(section), "missing section: $section")
        }
        assertTrue(markdown.contains("```mermaid"), "must contain mermaid diagrams")
        assertTrue(markdown.contains("OrderController"), "flows/hotspots must reference real types")
        assertTrue(markdown.contains("RestController"), "stereotype summary present")
    }

    @Test
    fun `generation is deterministic across runs`() {
        val root = buildIndexedRepo()
        val dbPath = root.resolve(".repomind/index.db")
        assertEquals(ReportGenerator(dbPath, "app").generate(), ReportGenerator(dbPath, "app").generate())
    }
}
