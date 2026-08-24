package dev.repomind.core.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Properties

object ConfigFileParser {

    fun flattenYaml(content: String): Map<String, String> {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any>(content) ?: return emptyMap()
        val result = mutableMapOf<String, String>()
        if (root is Map<*, *>) {
            flattenMap(root, prefix = "", result)
        }
        return result
    }

    private fun flattenMap(node: Map<*, *>, prefix: String, out: MutableMap<String, String>) {
        for ((rawKey, value) in node) {
            val key = rawKey.toString()
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            when (value) {
                is Map<*, *> -> flattenMap(value, fullKey, out)
                is List<*> -> flattenList(value, fullKey, out)
                else -> out[fullKey] = value.toString()
            }
        }
    }

    private fun flattenList(node: List<*>, prefix: String, out: MutableMap<String, String>) {
        node.forEachIndexed { index, value ->
            val fullKey = "$prefix[$index]"
            when (value) {
                is Map<*, *> -> flattenMap(value, fullKey, out)
                is List<*> -> flattenList(value, fullKey, out)
                else -> out[fullKey] = value.toString()
            }
        }
    }

    fun flattenProperties(content: String): Map<String, String> {
        val props = Properties()
        props.load(content.byteInputStream())
        return props.entries.associate { it.key.toString() to it.value.toString() }
    }

    fun parse(sourceFile: java.nio.file.Path, content: String): Map<String, String> {
        val name = sourceFile.fileName.toString().lowercase()
        return when {
            name.endsWith(".yml") || name.endsWith(".yaml") -> flattenYaml(content)
            name.endsWith(".properties") -> flattenProperties(content)
            else -> emptyMap()
        }
    }
}
