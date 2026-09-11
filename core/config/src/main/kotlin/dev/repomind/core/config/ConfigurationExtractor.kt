package dev.repomind.core.config

import dev.repomind.core.model.RepoModule
import dev.repomind.core.config.BindingKind
import dev.repomind.core.model.code.ModuleParse
import java.nio.file.Files
import java.nio.file.Path

class ConfigurationExtractor {

    fun extract(module: RepoModule, parsed: ModuleParse): ConfigGraph {
        val properties = collectProperties(module)
        val bindings = collectBindings(parsed)

        return ConfigGraph(
            moduleName = module.name,
            properties = properties.map { (key, valueByFile) ->
                val (file, value) = valueByFile.entries.first()
                ConfigProperty(key = key, value = value, sourceFile = file.toString())
            },
            bindings = bindings,
        )
    }

    private val CONFIG_FILES = Regex("""^application(-[A-Za-z0-9_-]+)?\.(yml|yaml|properties)$""")

    private fun collectProperties(module: RepoModule): Map<String, Map<Path, String>> {
        val merged = mutableMapOf<String, MutableMap<Path, String>>()
        for (sourceRoot in module.sourceRoots.filter { !it.isTest }) {
            val resources = sourceRoot.path.resolveSibling("resources")
            if (!resources.toFile().isDirectory) continue
            Files.walk(resources).use { stream ->
                stream.filter { it.toFile().isFile && CONFIG_FILES.matches(it.fileName.toString()) }
                    .forEach { file ->
                        for ((key, value) in ConfigFileParser.parse(file, Files.readString(file))) {
                            merged.getOrPut(key) { mutableMapOf() }[file] = value
                        }
                    }
            }
        }
        return merged
    }

    private fun collectBindings(parsed: ModuleParse): List<ConfigBinding> {
        val bindings = mutableListOf<ConfigBinding>()
        for (type in parsed.types) {
            val isConfigClass = type.annotations.any { it in setOf("Configuration", "SpringBootApplication") }
            if (isConfigClass) {
                bindings += ConfigBinding(
                    propertyKey = type.fqn,
                    targetFqn = type.fqn,
                    kind = BindingKind.CONFIGURATION_CLASS,
                )
            }
            type.configPrefix?.let { prefix ->
                bindings += ConfigBinding(
                    propertyKey = prefix,
                    targetFqn = type.fqn,
                    kind = BindingKind.CONFIGURATION_PROPERTIES,
                )
            }
            for (field in type.fields.filter { !it.synthetic }) {
                for (key in field.configKeys) {
                    bindings += ConfigBinding(
                        propertyKey = key,
                        targetFqn = type.fqn,
                        kind = BindingKind.VALUE_ANNOTATION,
                        memberName = field.name,
                    )
                }
            }
            for (method in type.methods.filter { !it.synthetic }) {
                if ("Bean" in method.annotations) {
                    bindings += ConfigBinding(
                        propertyKey = method.name,
                        targetFqn = "${type.fqn}#${method.name}",
                        kind = BindingKind.BEAN_METHOD,
                        memberName = method.name,
                        returnType = method.returnType,
                    )
                }
            }
        }
        return bindings
    }
}
