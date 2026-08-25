package dev.repomind.core.rules

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.nio.file.Files
import java.nio.file.Path

class RuleLoaderException(message: String) : RuntimeException(message)

object RuleLoader {

    fun load(path: Path): List<RuleDef> {
        if (!Files.isRegularFile(path)) return emptyList()
        return parse(Files.readString(path))
    }

    fun parse(yaml: String): List<RuleDef> {
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Any>(yaml)
            ?: return emptyList()
        val rulesNode = (root as? Map<*, *>)?.get("rules")
            ?: throw RuleLoaderException("rules file must contain a top-level 'rules:' list")

        val list = rulesNode as? List<*>
            ?: throw RuleLoaderException("'rules:' must be a list")

        return list.mapIndexed { index, entry ->
            val map = entry as? Map<*, *>
                ?: throw RuleLoaderException("rules[$index] must be a mapping")
            parseRule(map, index)
        }
    }

    private fun parseRule(map: Map<*, *>, index: Int): RuleDef {
        val name = map["name"]?.toString()
            ?: throw RuleLoaderException("rules[$index] missing 'name'")
        val from = stereotype(map["from"], "rules[$index].from")
            ?: throw RuleLoaderException("rules[$index] missing 'from'")
        val to = stereotype(map["to"], "rules[$index].to")
            ?: throw RuleLoaderException("rules[$index] missing 'to'")

        @Suppress("UNCHECKED_CAST")
        val edgeKinds = (map["edgeKinds"] as? List<String>)?.map { it.toString().uppercase() }
            ?: listOf("CALLS", "USES")

        return RuleDef(
            name = name,
            description = map["description"]?.toString(),
            from = from,
            to = to,
            message = map["message"]?.toString(),
            edgeKinds = edgeKinds,
        )
    }

    private fun stereotype(node: Any?, location: String): Stereotype? {
        val map = node as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val annotations = (map["annotations"] as? List<*>)?.map { it.toString() } ?: emptyList()
        val namePattern = map["namePattern"]?.toString()

        if (annotations.isEmpty() && namePattern == null) {
            throw RuleLoaderException("$location: stereotype needs 'annotations' and/or 'namePattern'")
        }
        return Stereotype(annotations = annotations, namePattern = namePattern)
    }
}
