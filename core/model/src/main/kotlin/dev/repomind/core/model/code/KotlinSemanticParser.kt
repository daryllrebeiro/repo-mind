package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.Visibility
import java.nio.file.Files
import java.nio.file.Path

/**
 * Lightweight semantic parser for Kotlin (.kt) source files.
 * Extracts classes, interfaces, objects, functions, properties, and dependencies.
 */
class KotlinSemanticParser : LanguageParser {

    override val languageId: String = "kotlin"
    override val supportedExtensions: Set<String> = setOf("kt", "kts")

    override fun parseModule(module: RepoModule, classpath: List<Path>): ModuleParse {
        val types = mutableListOf<ParsedType>()
        val edges = mutableListOf<DependencyEdge>()

        for (sourceRoot in module.sourceRoots) {
            val rootPath = sourceRoot.path
            if (!Files.isDirectory(rootPath)) continue

            rootPath.toFile().walkTopDown()
                .filter { it.isFile && it.extension in supportedExtensions }
                .forEach { file ->
                    parseFile(file.toPath(), sourceRoot.isTest, types, edges)
                }
        }

        return ModuleParse(
            moduleName = module.name,
            types = types,
            unresolvedSymbols = emptyList(),
            edges = edges.distinctBy { listOf(it.sourceFqn, it.targetFqn, it.kind, it.confidence) },
        )
    }

    private fun parseFile(
        filePath: Path,
        isTest: Boolean,
        types: MutableList<ParsedType>,
        edges: MutableList<DependencyEdge>,
    ) {
        val lines = Files.readAllLines(filePath)
        var packageName = ""
        val imports = mutableListOf<String>()

        // First pass: extract package and imports
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.removePrefix("package ").removeSuffix(";").trim()
            } else if (trimmed.startsWith("import ")) {
                val imp = trimmed.removePrefix("import ").removeSuffix(";").trim()
                imports += imp
            }
        }

        // Second pass: extract classes and top-level symbols
        var currentClassName: String? = null
        var currentClassLineStart = 1
        var currentClassAnnotations = mutableListOf<String>()
        val currentMethods = mutableListOf<ParsedMethod>()
        val currentFields = mutableListOf<ParsedField>()
        var currentClassKind = TypeKind.CLASS
        var superTypes = mutableListOf<String>()
        val propertyTypeMap = mutableMapOf<String, String>()
        var currentFunName: String? = null

        fun flushClass(endLine: Int) {
            val className = currentClassName ?: return
            val fqn = if (packageName.isNotBlank()) "$packageName.$className" else className

            types += ParsedType(
                fqn = fqn,
                kind = currentClassKind,
                packageName = packageName,
                filePath = filePath.toString().replace('\\', '/'),
                lineStart = currentClassLineStart,
                lineEnd = endLine,
                annotations = currentClassAnnotations.toList(),
                superTypeFqn = superTypes.firstOrNull(),
                interfaceFqns = if (superTypes.size > 1) superTypes.drop(1) else emptyList(),
                methods = currentMethods.toList(),
                fields = currentFields.toList(),
                isTest = isTest || className.endsWith("Test") || className.endsWith("Tests"),
            )

            // Imports as edges
            for (imp in imports) {
                edges += DependencyEdge(fqn, imp, EdgeKind.IMPORTS, Confidence.CONFIRMED, 1)
            }

            currentClassName = null
            currentMethods.clear()
            currentFields.clear()
            currentClassAnnotations.clear()
            superTypes.clear()
            propertyTypeMap.clear()
            currentFunName = null
        }

        for ((idx, line) in lines.withIndex()) {
            val lineNum = idx + 1
            val trimmed = line.trim()

            // Collect annotations
            if (trimmed.startsWith("@")) {
                val ann = trimmed.substringAfter('@').substringBefore('(').substringBefore(' ')
                currentClassAnnotations += ann
                continue
            }

            // Class, Interface, Object declarations
            val classMatch = Regex("^(public |internal |private |protected |open |abstract |data |sealed )*(class|interface|object|enum class) +([A-Za-z0-9_]+)").find(trimmed)
            if (classMatch != null) {
                if (currentClassName != null) {
                    flushClass(lineNum - 1)
                }
                val kindStr = classMatch.groupValues[2]
                val name = classMatch.groupValues[3]
                currentClassName = name
                currentClassLineStart = lineNum
                currentClassKind = when (kindStr) {
                    "interface" -> TypeKind.INTERFACE
                    "enum class" -> TypeKind.ENUM
                    else -> TypeKind.CLASS
                }

                // Extract constructor parameters: (val/var name: Type)
                val ctorParams = Regex("(val|var) +([A-Za-z0-9_]+) *: *([A-Za-z0-9_<>]+)").findAll(trimmed)
                for (paramMatch in ctorParams) {
                    val pName = paramMatch.groupValues[2]
                    val pType = paramMatch.groupValues[3].substringBefore('<')
                    propertyTypeMap[pName] = pType
                    currentFields += ParsedField(
                        name = pName,
                        declaredType = pType,
                        visibility = Visibility.PRIVATE,
                        isStatic = false,
                        annotations = emptyList(),
                        line = lineNum,
                    )
                    // Record USES edge to imported type
                    val targetFqn = imports.firstOrNull { it.endsWith(".$pType") }
                    if (targetFqn != null) {
                        val ownerFqn = if (packageName.isNotBlank()) "$packageName.$name" else name
                        edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.USES, Confidence.CONFIRMED, lineNum)
                    }
                }

                // Supertype inheritance ": SuperClass(), IInterface"
                if (":" in trimmed) {
                    val supers = trimmed.substringAfter(":").substringBefore("{").split(",")
                    for (s in supers) {
                        val simple = s.trim().substringBefore("(").substringBefore("<").trim()
                        if (simple.isNotBlank()) {
                            superTypes += simple
                            val targetFqn = imports.firstOrNull { it.endsWith(".$simple") } ?: if (packageName.isNotBlank()) "$packageName.$simple" else simple
                            edges += DependencyEdge(
                                sourceFqn = if (packageName.isNotBlank()) "$packageName.$name" else name,
                                targetFqn = targetFqn,
                                kind = if (currentClassKind == TypeKind.INTERFACE) EdgeKind.EXTENDS else EdgeKind.IMPLEMENTS,
                                confidence = Confidence.CONFIRMED,
                                line = lineNum,
                            )
                        }
                    }
                }
                continue
            }

            // Methods
            val funMatch = Regex("^(public |internal |private |protected |override |open )*fun +([A-Za-z0-9_]+) *\\((.*?)\\)").find(trimmed)
            if (funMatch != null) {
                val funName = funMatch.groupValues[2]
                currentFunName = funName
                val vis = if ("private" in trimmed) Visibility.PRIVATE else Visibility.PUBLIC
                currentMethods += ParsedMethod(
                    name = funName,
                    signature = "$funName()",
                    visibility = vis,
                    isStatic = false,
                    isAbstract = "abstract" in trimmed || currentClassKind == TypeKind.INTERFACE,
                    line = lineNum,
                )

                // Method parameters
                val paramsStr = funMatch.groupValues[3]
                if (paramsStr.isNotBlank()) {
                    val params = paramsStr.split(",")
                    for (p in params) {
                        val parts = p.trim().split(":")
                        if (parts.size == 2) {
                            val pName = parts[0].trim().substringAfterLast(" ")
                            val pType = parts[1].trim().substringBefore("<").substringBefore("=")
                            propertyTypeMap[pName] = pType
                            val targetFqn = imports.firstOrNull { it.endsWith(".$pType") }
                            val cls = currentClassName
                            if (targetFqn != null && cls != null) {
                                val ownerFqn = if (packageName.isNotBlank()) "$packageName.$cls" else cls
                                edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.USES, Confidence.CONFIRMED, lineNum, callerMember = funName)
                            }
                        }
                    }
                }
                continue
            }

            // Properties
            val propMatch = Regex("^(public |internal |private |protected )*(val|var) +([A-Za-z0-9_]+) *: *([A-Za-z0-9_<>]+)").find(trimmed)
            if (propMatch != null) {
                val propName = propMatch.groupValues[3]
                val propType = propMatch.groupValues[4]
                propertyTypeMap[propName] = propType
                val vis = if ("private" in trimmed) Visibility.PRIVATE else Visibility.PUBLIC
                currentFields += ParsedField(
                    name = propName,
                    declaredType = propType,
                    visibility = vis,
                    isStatic = false,
                    annotations = emptyList(),
                    line = lineNum,
                )
                continue
            }

            // Check calls within body: property.<method>(...) or Type.<method>(...)
            val cls = currentClassName
            if (cls != null) {
                val ownerFqn = if (packageName.isNotBlank()) "$packageName.$cls" else cls

                for ((propName, propType) in propertyTypeMap) {
                    if (Regex("\\b$propName\\.[A-Za-z0-9_]+\\(").containsMatchIn(trimmed)) {
                        val targetFqn = imports.firstOrNull { it.endsWith(".$propType") } ?: if (packageName.isNotBlank()) "$packageName.$propType" else propType
                        edges += DependencyEdge(
                            sourceFqn = ownerFqn,
                            targetFqn = targetFqn,
                            kind = EdgeKind.CALLS,
                            confidence = Confidence.CONFIRMED,
                            line = lineNum,
                            callerMember = currentFunName,
                        )
                    }
                }

                for (imp in imports) {
                    val simpleName = imp.substringAfterLast('.')
                    if (Regex("\\b$simpleName\\b").containsMatchIn(trimmed)) {
                        edges += DependencyEdge(
                            sourceFqn = ownerFqn,
                            targetFqn = imp,
                            kind = EdgeKind.CALLS,
                            confidence = Confidence.POSSIBLE,
                            line = lineNum,
                            callerMember = currentFunName,
                        )
                    }
                }
            }
        }

        if (currentClassName != null) {
            flushClass(lines.size)
        }
    }
}
