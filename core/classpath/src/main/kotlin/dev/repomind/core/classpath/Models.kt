package dev.repomind.core.classpath

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class CachedClasspath(
    val buildFileHash: String,
    val entries: List<String>,
)

enum class DiagnosticSeverity { INFO, WARNING, ERROR }

@Serializable
data class ClasspathDiagnostic(
    val severity: DiagnosticSeverity,
    val message: String,
    val details: String? = null,
    val exitCode: Int? = null,
)

data class ResolvedClasspath(
    val module: Path,
    val entries: List<Path>,
    val fromCache: Boolean,
    val diagnostics: List<ClasspathDiagnostic> = emptyList(),
    val isSuccess: Boolean = true,
)

class ClasspathResolutionException(
    val moduleName: String,
    message: String,
    val stderr: String? = null,
    val exitCode: Int? = null,
) : RuntimeException(message)
