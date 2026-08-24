package dev.repomind.core.classpath

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class CachedClasspath(
    val buildFileHash: String,
    val entries: List<String>,
)

data class ResolvedClasspath(
    val module: Path,
    val entries: List<Path>,
    val fromCache: Boolean,
)

class ClasspathResolutionException(
    val moduleName: String,
    message: String,
    val stderr: String? = null,
) : RuntimeException(message)
