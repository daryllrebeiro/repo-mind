package dev.repomind.core.model

import java.nio.file.Path

enum class BuildSystem { MAVEN, GRADLE_KOTLIN, GRADLE_GROOVY, UNKNOWN }

enum class Visibility { PUBLIC, PROTECTED, PRIVATE, PACKAGE }

data class SourceRoot(val path: Path, val isTest: Boolean)

data class RepoModule(
    val name: String,
    val path: Path,
    val buildFile: Path?,
    val sourceRoots: List<SourceRoot>,
)

data class ScanResult(
    val root: Path,
    val buildSystem: BuildSystem,
    val modules: List<RepoModule>,
    val sourceFileCount: Int,
)
