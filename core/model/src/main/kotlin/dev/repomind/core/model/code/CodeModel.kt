package dev.repomind.core.model.code

import dev.repomind.core.model.Visibility

enum class TypeKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }

enum class EdgeKind { IMPORTS, EXTENDS, IMPLEMENTS, USES, CALLS, TESTS }

enum class Confidence { CONFIRMED, POSSIBLE }

data class DependencyEdge(
    val sourceFqn: String,
    val targetFqn: String,
    val kind: EdgeKind,
    val confidence: Confidence,
    val line: Int = 0,
)

data class ParsedMethod(
    val name: String,
    val signature: String,
    val visibility: Visibility,
    val isStatic: Boolean,
    val isAbstract: Boolean,
    val synthetic: Boolean = false,
    val line: Int,
)

data class ParsedField(
    val name: String,
    val declaredType: String,
    val visibility: Visibility,
    val isStatic: Boolean,
    val annotations: List<String>,
    val configKeys: List<String> = emptyList(),
    val synthetic: Boolean = false,
    val line: Int,
)

data class ParsedType(
    val fqn: String,
    val kind: TypeKind,
    val packageName: String,
    val filePath: String,
    val lineStart: Int,
    val lineEnd: Int,
    val annotations: List<String>,
    val configPrefix: String? = null,
    val superTypeFqn: String?,
    val interfaceFqns: List<String>,
    val methods: List<ParsedMethod>,
    val fields: List<ParsedField>,
    val isTest: Boolean = false,
)

data class UnresolvedSymbol(
    val symbol: String,
    val filePath: String,
    val line: Int,
)

data class ModuleParse(
    val moduleName: String,
    val types: List<ParsedType>,
    val unresolvedSymbols: List<UnresolvedSymbol>,
    val edges: List<DependencyEdge> = emptyList(),
) {
    val typeCount: Int get() = types.size
    val unresolvedCount: Int get() = unresolvedSymbols.size
}
