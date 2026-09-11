package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import java.nio.file.Path

/**
 * Service Provider Interface (SPI) for language-specific semantic parsing.
 * Allows RepoMind to support multiple programming languages (Java, Kotlin, etc.)
 * in a modular and extensible architecture.
 */
interface LanguageParser {
    val languageId: String
    val supportedExtensions: Set<String>

    fun canHandle(file: Path): Boolean {
        val ext = file.fileName.toString().substringAfterLast('.', "")
        return ext in supportedExtensions
    }

    fun parseModule(module: RepoModule, classpath: List<Path> = emptyList()): ModuleParse
}
