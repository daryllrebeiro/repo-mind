package dev.repomind.core.report

import dev.repomind.storage.sqlite.SymbolDatabase
import dev.repomind.storage.sqlite.SymbolEmbeddingRow
import dev.repomind.storage.sqlite.SymbolRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt

@Serializable
data class GraphRagDocument(
    val id: String,
    val symbolFqn: String,
    val kind: String,
    val module: String,
    val filePath: String?,
    val lineRange: String,
    val summaryText: String,
    val incomingCallers: List<String>,
    val outgoingCallees: List<String>,
    val blastRadiusCount: Int,
    val annotations: List<String>,
    val embedding: List<Float>,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class GraphRagExportResult(
    val documentsExported: Int,
    val totalDimensions: Int,
    val outputPath: String?,
    val persistedToDb: Boolean,
)

/**
 * Generates hybrid Graph-RAG documents combining semantic symbol summaries,
 * call graph topology (callers, callees, transitive blast radius), and normalized vector embeddings.
 */
class GraphRagExporter(
    val dimensions: Int = 64
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun export(
        db: SymbolDatabase,
        outputFile: Path? = null,
        persistToDb: Boolean = true,
    ): GraphRagExportResult {
        val allSymbols = db.allSymbols()
        val documents = mutableListOf<GraphRagDocument>()
        val dbEmbeddingRows = mutableListOf<SymbolEmbeddingRow>()
        val now = System.currentTimeMillis()

        for (sym in allSymbols) {
            val callers = db.edges.transitiveCallers(sym.qualifiedName, maxDepth = 1).toList()
            val callees = db.edges.transitiveCallees(sym.qualifiedName, maxDepth = 1).toList()
            val blastRadius = db.edges.transitiveCallers(sym.qualifiedName, maxDepth = 3)

            val summaryText = buildSummary(sym, callers, callees, blastRadius.size)
            val embedding = computeEmbedding(summaryText, sym.qualifiedName, dimensions)

            val doc = GraphRagDocument(
                id = sym.qualifiedName,
                symbolFqn = sym.qualifiedName,
                kind = sym.kind,
                module = sym.module,
                filePath = sym.filePath,
                lineRange = "${sym.lineStart}-${sym.lineEnd}",
                summaryText = summaryText,
                incomingCallers = callers,
                outgoingCallees = callees,
                blastRadiusCount = blastRadius.size,
                annotations = sym.annotations,
                embedding = embedding,
                metadata = mapOf(
                    "visibility" to sym.visibility,
                    "parentFqn" to (sym.parentFqn ?: ""),
                ),
            )
            documents += doc

            if (persistToDb) {
                dbEmbeddingRows += SymbolEmbeddingRow(
                    symbolFqn = sym.qualifiedName,
                    module = sym.module,
                    kind = sym.kind,
                    summaryText = summaryText,
                    docstring = null,
                    embedding = embedding,
                    dimensions = dimensions,
                    updatedAt = now,
                )
            }
        }

        if (persistToDb && dbEmbeddingRows.isNotEmpty()) {
            db.recordEmbeddings(dbEmbeddingRows)
        }

        if (outputFile != null) {
            Files.createDirectories(outputFile.parent ?: Path.of("."))
            val content = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(GraphRagDocument.serializer()),
                documents,
            )
            Files.writeString(outputFile, content)
        }

        return GraphRagExportResult(
            documentsExported = documents.size,
            totalDimensions = dimensions,
            outputPath = outputFile?.toAbsolutePath()?.normalize()?.toString(),
            persistedToDb = persistToDb,
        )
    }

    private fun buildSummary(
        sym: SymbolRow,
        callers: List<String>,
        callees: List<String>,
        blastRadiusSize: Int,
    ): String {
        return buildString {
            append("${sym.kind} ${sym.qualifiedName}")
            if (!sym.filePath.isNullOrBlank()) {
                append(" located in ${sym.filePath}:${sym.lineStart}-${sym.lineEnd}")
            }
            if (sym.annotations.isNotEmpty()) {
                append(". Annotations: ${sym.annotations.joinToString(", ")}")
            }
            if (callers.isNotEmpty()) {
                append(". Referenced by: ${callers.take(5).joinToString(", ")}")
            }
            if (callees.isNotEmpty()) {
                append(". Invocations: ${callees.take(5).joinToString(", ")}")
            }
            if (blastRadiusSize > 0) {
                append(". Transitive impact radius: $blastRadiusSize symbols")
            }
        }
    }

    fun computeEmbedding(text: String, fqn: String, dims: Int): List<Float> {
        val vector = FloatArray(dims)
        val tokens = text.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 2 }
        val fqnParts = fqn.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.isNotBlank() }

        for (token in tokens) {
            val hash = token.hashCode()
            val dim = kotlin.math.abs(hash) % dims
            val sign = if (hash >= 0) 1.0f else -1.0f
            vector[dim] += sign * 1.0f

            // Character n-grams for typo & subword tolerance
            if (token.length >= 3) {
                for (i in 0..token.length - 3) {
                    val gram = token.substring(i, i + 3)
                    val gHash = gram.hashCode()
                    val gDim = kotlin.math.abs(gHash) % dims
                    val gSign = if (gHash >= 0) 0.5f else -0.5f
                    vector[gDim] += gSign
                }
            }
        }

        // Give extra weight to explicit qualified name components
        for (part in fqnParts) {
            val hash = part.hashCode()
            val dim = kotlin.math.abs(hash) % dims
            val sign = if (hash >= 0) 2.0f else -2.0f
            vector[dim] += sign
        }

        // L2 normalize vector
        var normSq = 0.0f
        for (v in vector) normSq += v * v
        val norm = sqrt(normSq.toDouble()).toFloat()
        if (norm > 0.0f) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector.toList()
    }
}
