package dev.repomind.core.config

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepoMindConfigTest {

    @Test
    fun `loads default config when no repomind yml file is present`() {
        val tempDir = Files.createTempDirectory("no-config-repo")
        val config = RepoMindConfigLoader.load(tempDir)

        assertEquals(listOf("java", "kotlin"), config.languages)
        assertEquals(emptyList(), config.excludePatterns)
        assertEquals(10, config.maxCallDepth)
        assertEquals(".repomind/rules.yaml", config.rulesFile)
    }

    @Test
    fun `parses custom repomind yml file`() {
        val yaml = """
            languages:
              - java
              - kotlin
            excludePatterns:
              - "**/generated/**"
              - "**/target/**"
            maxCallDepth: 5
            rulesFile: "custom-rules.yaml"
        """.trimIndent()

        val config = RepoMindConfigLoader.parse(yaml)

        assertEquals(listOf("java", "kotlin"), config.languages)
        assertEquals(listOf("**/generated/**", "**/target/**"), config.excludePatterns)
        assertEquals(5, config.maxCallDepth)
        assertEquals("custom-rules.yaml", config.rulesFile)
    }

    @Test
    fun `loads repomind yml from repository root`() {
        val root = Files.createTempDirectory("config-repo")
        root.resolve(".repomind.yml").writeText(
            """
            languages: [kotlin]
            maxCallDepth: 7
            """.trimIndent(),
        )

        val config = RepoMindConfigLoader.load(root)
        assertEquals(listOf("kotlin"), config.languages)
        assertEquals(7, config.maxCallDepth)
    }
}
