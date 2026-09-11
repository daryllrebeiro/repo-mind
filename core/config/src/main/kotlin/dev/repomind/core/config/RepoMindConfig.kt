package dev.repomind.core.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

data class RepoMindProjectConfig(
    val languages: List<String> = listOf("java", "kotlin"),
    val excludePatterns: List<String> = emptyList(),
    val maxCallDepth: Int = 10,
    val rulesFile: String = ".repomind/rules.yaml",
)

object RepoMindConfigLoader {

    fun load(repoRoot: Path): RepoMindProjectConfig {
        val candidates = listOf(
            repoRoot.resolve(".repomind.yml"),
            repoRoot.resolve(".repomind.yaml"),
            repoRoot.resolve(".repomind/repomind.yml"),
            repoRoot.resolve(".repomind/repomind.yaml"),
        )
        val configFile = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: return RepoMindProjectConfig()

        return parse(Files.readString(configFile))
    }

    fun parse(yamlContent: String): RepoMindProjectConfig {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any>(yamlContent) as? Map<*, *>
            ?: return RepoMindProjectConfig()

        @Suppress("UNCHECKED_CAST")
        val languages = (root["languages"] as? List<*>)?.map { it.toString().lowercase() }
            ?: listOf("java", "kotlin")

        @Suppress("UNCHECKED_CAST")
        val excludePatterns = (root["excludePatterns"] as? List<*>)?.map { it.toString() }
            ?: emptyList()

        val maxCallDepth = (root["maxCallDepth"] as? Number)?.toInt() ?: 10
        val rulesFile = root["rulesFile"]?.toString() ?: ".repomind/rules.yaml"

        return RepoMindProjectConfig(
            languages = languages,
            excludePatterns = excludePatterns,
            maxCallDepth = maxCallDepth,
            rulesFile = rulesFile,
        )
    }
}
