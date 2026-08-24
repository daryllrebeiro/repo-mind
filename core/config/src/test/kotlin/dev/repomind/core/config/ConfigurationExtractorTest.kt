package dev.repomind.core.config

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.core.model.Visibility
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedField
import dev.repomind.core.model.code.ParsedMethod
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.TypeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigurationExtractorTest {

    private fun moduleWithResources(vararg files: Pair<String, String>): RepoModule {
        val root = Files.createTempDirectory("repomind-config")
        val resources = root.resolve("src/main/resources")
        for ((relPath, content) in files) {
            val file = resources.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return RepoModule(
            name = "cfg-module",
            path = root,
            buildFile = null,
            sourceRoots = listOf(SourceRoot(root.resolve("src/main/java"), isTest = false)),
        )
    }

    private fun type(
        fqn: String,
        prefix: String? = null,
        fields: List<ParsedField> = emptyList(),
    ) = ParsedType(
        fqn = fqn,
        kind = TypeKind.CLASS,
        packageName = fqn.substringBeforeLast('.'),
        filePath = "/x/$fqn.java",
        lineStart = 1,
        lineEnd = 10,
        annotations = if (prefix != null) listOf("ConfigurationProperties") else emptyList(),
        configPrefix = prefix,
        superTypeFqn = null,
        interfaceFqns = emptyList(),
        methods = emptyList(),
        fields = fields,
    )

    private fun field(name: String, keys: List<String>) = ParsedField(
        name = name,
        declaredType = "String",
        visibility = Visibility.PRIVATE,
        isStatic = false,
        annotations = if (keys.isNotEmpty()) listOf("Value") else emptyList(),
        configKeys = keys,
        line = 3,
    )

    @Test
    fun `collects yaml properties from resources`() {
        val module = moduleWithResources(
            "application.yml" to """
                app:
                  mail:
                    host: smtp.example.com
                    port: 587
            """.trimIndent(),
        )

        val graph = ConfigurationExtractor().extract(module, ModuleParse(module.name, emptyList(), emptyList()))

        assertEquals(2, graph.properties.size)
        assertEquals("smtp.example.com", graph.properties.first { it.key == "app.mail.host" }.value)
    }

    @Test
    fun `binds configuration prefix to config class`() {
        val parsed = ModuleParse(
            moduleName = "cfg-module",
            types = listOf(type("com.example.MailProperties", prefix = "app.mail")),
            unresolvedSymbols = emptyList(),
        )
        val module = moduleWithResources("application.yml" to "app:\n  mail:\n    host: x\n")

        val graph = ConfigurationExtractor().extract(module, parsed)

        assertEquals(1, graph.bindings.size)
        val binding = graph.bindings.single()
        assertEquals("app.mail", binding.propertyKey)
        assertEquals("com.example.MailProperties", binding.targetFqn)
        assertEquals(dev.repomind.core.config.BindingKind.CONFIGURATION_PROPERTIES, binding.kind)
        assertTrue(graph.bindingsFor("app.mail.host").isNotEmpty(), "child keys must resolve to the prefix binding")
    }

    @Test
    fun `binds value placeholders to fields`() {
        val parsed = ModuleParse(
            moduleName = "cfg-module",
            types = listOf(
                type(
                    "com.example.Worker",
                    fields = listOf(field("queueName", listOf("app.queue.name"))),
                ),
            ),
            unresolvedSymbols = emptyList(),
        )
        val module = moduleWithResources()

        val graph = ConfigurationExtractor().extract(module, parsed)

        val binding = graph.bindings.single()
        assertEquals("app.queue.name", binding.propertyKey)
        assertEquals("queueName", binding.memberName)
        assertEquals(dev.repomind.core.config.BindingKind.VALUE_ANNOTATION, binding.kind)
    }

    @Test
    fun `later profile files override base application yml`() {
        val module = moduleWithResources(
            "application.yml" to "server:\n  port: 8080\n",
            "application-prod.yml" to "server:\n  port: 9090\n",
        )

        val graph = ConfigurationExtractor().extract(module, ModuleParse(module.name, emptyList(), emptyList()))

        val port = graph.properties.first { it.key == "server.port" }
        assertEquals("9090", port.value)
    }
}

