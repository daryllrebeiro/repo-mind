package dev.repomind.core.index

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class IncrementalResult(
    val addedFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int,
    val reparsedModules: List<String>,
    val skippedModules: Int,
    val symbolsIndexed: Int,
    val edgesIndexed: Int,
    val fullIndex: Boolean,
    val elapsedMs: Long,
)
