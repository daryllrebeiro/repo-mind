package dev.repomind.core.report

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.TypeKind
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphRagExporterTest {

    @Test
    fun `exports graph rag documents with embeddings callers and blast radii`() {
        val tempDir = Files.createTempDirectory("graph-rag-test")
        val dbPath = tempDir.resolve("index.db")
        val outputFile = tempDir.resolve("graph-rag.json")

        SymbolDatabase.open(dbPath).use { db ->
            db.recordModule("billing", tempDir.toString(), "GRADLE")
            val parse = ModuleParse(
                moduleName = "billing",
                types = listOf(
                    ParsedType(
                        fqn = "com.example.billing.OrderService",
                        kind = TypeKind.CLASS,
                        packageName = "com.example.billing",
                        filePath = "OrderService.java",
                        lineStart = 10,
                        lineEnd = 50,
                        annotations = listOf("Service", "Transactional"),
                        superTypeFqn = null,
                        interfaceFqns = emptyList(),
                        methods = emptyList(),
                        fields = emptyList(),
                    ),
                    ParsedType(
                        fqn = "com.example.billing.PaymentGateway",
                        kind = TypeKind.INTERFACE,
                        packageName = "com.example.billing",
                        filePath = "PaymentGateway.java",
                        lineStart = 1,
                        lineEnd = 15,
                        annotations = emptyList(),
                        superTypeFqn = null,
                        interfaceFqns = emptyList(),
                        methods = emptyList(),
                        fields = emptyList(),
                    ),
                ),
                unresolvedSymbols = emptyList(),
                edges = listOf(
                    DependencyEdge(
                        sourceFqn = "com.example.billing.OrderService",
                        targetFqn = "com.example.billing.PaymentGateway",
                        kind = EdgeKind.CALLS,
                        confidence = Confidence.CONFIRMED,
                    ),
                ),
            )
            db.replaceModule("billing", parse)
            db.edges.replaceModule("billing", parse.edges)

            val exporter = GraphRagExporter(dimensions = 32)
            val result = exporter.export(db, outputFile = outputFile, persistToDb = true)

            assertEquals(2, result.documentsExported)
            assertEquals(32, result.totalDimensions)
            assertTrue(result.persistedToDb)
            assertTrue(Files.isRegularFile(outputFile))

            val fileContent = Files.readString(outputFile)
            assertTrue(fileContent.contains("com.example.billing.OrderService"))
            assertTrue(fileContent.contains("com.example.billing.PaymentGateway"))
            assertTrue(fileContent.contains("Transitive impact radius"))

            // Verify embeddings persisted into SQLite table
            val embeddings = db.allEmbeddings()
            assertEquals(2, embeddings.size)

            val orderEmbedding = db.findEmbedding("com.example.billing.OrderService")
            assertNotNull(orderEmbedding)
            assertEquals("billing", orderEmbedding.module)
            assertEquals(32, orderEmbedding.dimensions)
            assertEquals(32, orderEmbedding.embedding.size)

            val gatewayEmbedding = db.findEmbedding("com.example.billing.PaymentGateway")
            assertNotNull(gatewayEmbedding)
            assertEquals(32, gatewayEmbedding.embedding.size)
        }
    }
}
