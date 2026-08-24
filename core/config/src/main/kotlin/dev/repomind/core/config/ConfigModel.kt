package dev.repomind.core.config

import kotlinx.serialization.Serializable

@Serializable
data class ConfigProperty(
    val key: String,
    val value: String,
    val sourceFile: String,
)

enum class BindingKind { CONFIGURATION_PROPERTIES, VALUE_ANNOTATION }

@Serializable
data class ConfigBinding(
    val propertyKey: String,
    val targetFqn: String,
    val kind: BindingKind,
    val memberName: String? = null,
)

data class ConfigGraph(
    val moduleName: String,
    val properties: List<ConfigProperty>,
    val bindings: List<ConfigBinding>,
) {
    fun bindingsFor(key: String): List<ConfigBinding> =
        bindings.filter { b -> key == b.propertyKey || key.startsWith("${b.propertyKey}.") }
}
