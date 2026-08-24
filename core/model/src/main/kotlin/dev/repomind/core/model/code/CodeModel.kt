package dev.repomind.core.model.code

import dev.repomind.core.model.Visibility

enum class TypeKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }

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
    val superTypeFqn: String?,
    val interfaceFqns: List<String>,
    val methods: List<ParsedMethod>,
    val fields: List<ParsedField>,
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
) {
    val typeCount: Int get() = types.size
    val unresolvedCount: Int get() = unresolvedSymbols.size
}
