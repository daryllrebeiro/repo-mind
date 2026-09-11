package dev.repomind.language.java

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class ConcurrentParsingTest {

    private fun sampleModule(): RepoModule {
        val root = Files.createTempDirectory("repomind-concurrent-test")
        val src = root.resolve("src/main/java")
        val files = mapOf(
            "com/example/OrderService.java" to """
                package com.example;
                public class OrderService {
                    private final OrderRepository repo;
                    public OrderService(OrderRepository repo) { this.repo = repo; }
                    public void process(Order order) {
                        repo.save(order);
                    }
                }
            """.trimIndent(),
            "com/example/OrderRepository.java" to """
                package com.example;
                public interface OrderRepository {
                    void save(Order order);
                }
            """.trimIndent(),
            "com/example/Order.java" to """
                package com.example;
                public class Order {
                    private String id;
                    public String getId() { return id; }
                }
            """.trimIndent(),
        )

        for ((relPath, content) in files) {
            val file = src.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }

        return RepoModule(
            name = "concurrent-module",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(src, isTest = false)),
        )
    }

    @Test
    fun `race detector runs module concurrently with identical output`() = runBlocking {
        val module = sampleModule()
        val parser = JavaSemanticParser()

        val baseline = parser.parseModuleConcurrent(module, emptyList(), threads = 1)

        val concurrentRuns = (1..10).map {
            async(Dispatchers.Default) {
                parser.parseModuleConcurrent(module, emptyList(), threads = 4)
            }
        }.awaitAll()

        for (run in concurrentRuns) {
            assertEquals(baseline.types.map { it.fqn }, run.types.map { it.fqn })
            assertEquals(baseline.edges.map { Triple(it.sourceFqn, it.targetFqn, it.kind) }, run.edges.map { Triple(it.sourceFqn, it.targetFqn, it.kind) })
            assertEquals(baseline.unresolvedSymbols, run.unresolvedSymbols)
        }
    }

    @Test
    fun `synchronous parseModule SPI delegates to concurrent parser cleanly`() {
        val module = sampleModule()
        val parser = JavaSemanticParser()

        val result = parser.parseModule(module, emptyList())
        assertEquals(3, result.types.size)
        assertEquals(listOf("com.example.Order", "com.example.OrderRepository", "com.example.OrderService"), result.types.map { it.fqn })
    }
}
