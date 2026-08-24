package dev.repomind.language.java

import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import dev.repomind.core.model.code.ParsedField
import dev.repomind.core.model.code.ParsedMethod

object LombokSynthesizer {

    private val CLASS_LEVEL_DATA = setOf("Data")
    private val GETTER_ANNOTATIONS = setOf("Getter")
    private val SETTER_ANNOTATIONS = setOf("Setter")
    private val LOGGER_TYPES = mapOf(
        "Slf4j" to "org.slf4j.Logger",
        "Log4j2" to "org.apache.logging.log4j.Logger",
        "CommonsLog" to "org.apache.commons.logging.Log",
    )

    fun synthesizeMembers(typeDecl: TypeDeclaration<*>): List<ParsedMethod> {
        val annotations = typeDecl.annotations.map { it.nameAsString.substringAfterLast('.') }.toSet()
        val synthesized = mutableListOf<ParsedMethod>()

        if (annotations.any { it in CLASS_LEVEL_DATA || it in GETTER_ANNOTATIONS }) {
            typeDecl.fields
                .filter { !it.isStatic }
                .flatMap { it.variables }
                .forEach { v ->
                    val cap = v.nameAsString.replaceFirstChar { it.uppercase() }
                    synthesized += ParsedMethod(
                        name = "get$cap",
                        signature = "get$cap()",
                        visibility = dev.repomind.core.model.Visibility.PUBLIC,
                        isStatic = false,
                        isAbstract = false,
                        synthetic = true,
                        line = 0,
                    )
                }
        }

        if (annotations.any { it in CLASS_LEVEL_DATA || it in SETTER_ANNOTATIONS }) {
            typeDecl.fields
                .filter { !it.isStatic && it.isFinal.not() }
                .flatMap { it.variables }
                .forEach { v ->
                    val cap = v.nameAsString.replaceFirstChar { it.uppercase() }
                    synthesized += ParsedMethod(
                        name = "set$cap",
                        signature = "set$cap(${v.type.asString()})",
                        visibility = dev.repomind.core.model.Visibility.PUBLIC,
                        isStatic = false,
                        isAbstract = false,
                        synthetic = true,
                        line = 0,
                    )
                }
        }

        if (annotations.contains("RequiredArgsConstructor") || annotations.contains("AllArgsConstructor")) {
            val ctorFields = typeDecl.fields.filter { !it.isStatic && (!hasInitializer(it) || it.isFinal) }
            synthesized += ParsedMethod(
                name = "<init>",
                signature = "<init>(${ctorFields.flatMap { it.variables }.joinToString(",") { v -> v.type.asString() }})",
                visibility = dev.repomind.core.model.Visibility.PUBLIC,
                isStatic = false,
                isAbstract = false,
                synthetic = true,
                line = 0,
            )
        }

        if (annotations.contains("Builder")) {
            synthesized += ParsedMethod(
                name = "builder",
                signature = "builder()",
                visibility = dev.repomind.core.model.Visibility.PUBLIC,
                isStatic = true,
                isAbstract = false,
                synthetic = true,
                line = 0,
            )
        }

        if (annotations.contains("Data")) {
            for (name in listOf("equals", "hashCode", "toString")) {
                synthesized += ParsedMethod(
                    name = name,
                    signature = "$name()",
                    visibility = dev.repomind.core.model.Visibility.PUBLIC,
                    isStatic = false,
                    isAbstract = false,
                    synthetic = true,
                    line = 0,
                )
            }
        }

        return synthesized
    }

    fun synthesizeFields(typeDecl: TypeDeclaration<*>): List<ParsedField> {
        val annotations = typeDecl.annotations.map { it.nameAsString.substringAfterLast('.') }.toSet()
        return annotations.flatMap { ann ->
            LOGGER_TYPES[ann]?.let { loggerType ->
                listOf(
                    ParsedField(
                        name = "log",
                        declaredType = loggerType,
                        visibility = dev.repomind.core.model.Visibility.PRIVATE,
                        isStatic = true,
                        annotations = emptyList(),
                        synthetic = true,
                        line = 0,
                    )
                )
            } ?: emptyList()
        }
    }

    private fun hasInitializer(field: FieldDeclaration): Boolean =
        field.variables.any { it.initializer.isPresent }
}
