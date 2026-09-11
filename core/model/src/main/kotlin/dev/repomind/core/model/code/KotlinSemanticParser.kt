package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.Visibility
import java.nio.file.Files
import java.nio.file.Path

/**
 * Semantic parser for Kotlin (.kt, .kts) source files.
 * Extracts classes, interfaces, objects, companion objects, extension functions,
 * top-level functions (synthetic Kt classes), constructor dependencies, and call edges.
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
            edges = edges.distinctBy { listOf(it.sourceFqn, it.targetFqn, it.kind, it.confidence, it.callerMember) },
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
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ")) {
                packageName = trimmed.removePrefix("package ").removeSuffix(";").trim()
            } else if (trimmed.startsWith("import ")) {
                val imp = trimmed.removePrefix("import ").removeSuffix(";").trim()
                imports += imp
            }
        }

        // Second pass: extract classes, objects, functions, properties
        var currentClassName: String? = null
        var currentClassLineStart = 1
        var currentClassAnnotations = mutableListOf<String>()
        val currentMethods = mutableListOf<ParsedMethod>()
        val currentFields = mutableListOf<ParsedField>()
        var currentClassKind = TypeKind.CLASS
        var superTypes = mutableListOf<String>()
        val propertyTypeMap = mutableMapOf<String, String>()
        var currentFunName: String? = null

        fun ensureClassForTopLevel(startLine: Int) {
            if (currentClassName == null) {
                val simpleName = filePath.fileName.toString().substringBeforeLast('.')
                val fileClass = simpleName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } + "Kt"
                currentClassName = fileClass
                currentClassLineStart = startLine
                currentClassKind = TypeKind.CLASS
            }
        }

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

            // Class, Interface, Object, Companion Object declarations
            val classMatch = Regex("^(public |internal |private |protected |open |abstract |data |sealed |annotation |value )*(class|interface|object|enum class|annotation class|companion object)( +([A-Za-z0-9_]+))?").find(trimmed)
            if (classMatch != null && !trimmed.startsWith("fun ")) {
                val kindStr = classMatch.groupValues[2]
                val matchedName = classMatch.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }
                val name = matchedName ?: if (kindStr == "companion object") "Companion" else null

                if (name != null) {
                    if (currentClassName != null) {
                        flushClass(lineNum - 1)
                    }
                    currentClassName = name
                    currentClassLineStart = lineNum
                    currentClassKind = when (kindStr) {
                        "interface" -> TypeKind.INTERFACE
                        "enum class" -> TypeKind.ENUM
                        "annotation class" -> TypeKind.ANNOTATION
                        else -> TypeKind.CLASS
                    }

                    // Extract constructor parameters: (val/var name: Type)
                    val ctorParams = Regex("(@[A-Za-z0-9_]+ +)?(val|var) +([A-Za-z0-9_]+) *: *([A-Za-z0-9_<>]+)").findAll(trimmed)
                    for (paramMatch in ctorParams) {
                        val pAnnotation = paramMatch.groupValues[1].trim().removePrefix("@").takeIf { it.isNotBlank() }
                        val pName = paramMatch.groupValues[3]
                        val pType = paramMatch.groupValues[4].substringBefore('<')
                        propertyTypeMap[pName] = pType
                        currentFields += ParsedField(
                            name = pName,
                            declaredType = pType,
                            visibility = Visibility.PRIVATE,
                            isStatic = false,
                            annotations = listOfNotNull(pAnnotation),
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
            }

            // Extension functions: fun Receiver.methodName(...)
            val extFunMatch = Regex("^(public |internal |private |protected |override |open |inline |suspend )*fun *(<.*?>)? *([A-Za-z0-9_<>]+)\\.([A-Za-z0-9_]+) *\\((.*?)\\)").find(trimmed)
            if (extFunMatch != null) {
                ensureClassForTopLevel(lineNum)
                val receiverType = extFunMatch.groupValues[3].substringBefore('<')
                val funName = extFunMatch.groupValues[4]
                val paramsStr = extFunMatch.groupValues[5]
                currentFunName = funName

                val vis = if ("private" in trimmed) Visibility.PRIVATE else Visibility.PUBLIC
                currentMethods += ParsedMethod(
                    name = funName,
                    signature = "$receiverType.$funName()",
                    visibility = vis,
                    isStatic = false,
                    isAbstract = false,
                    line = lineNum,
                )

                val cls = currentClassName!!
                val ownerFqn = if (packageName.isNotBlank()) "$packageName.$cls" else cls

                // USES edge to receiver type
                val receiverFqn = imports.firstOrNull { it.endsWith(".$receiverType") } ?: if (packageName.isNotBlank()) "$packageName.$receiverType" else receiverType
                edges += DependencyEdge(
                    sourceFqn = ownerFqn,
                    targetFqn = receiverFqn,
                    kind = EdgeKind.USES,
                    confidence = Confidence.CONFIRMED,
                    line = lineNum,
                    callerMember = funName,
                )

                // Parse params
                parseParams(paramsStr, propertyTypeMap, imports, ownerFqn, funName, lineNum, edges)
                continue
            }

            // Standard functions: fun methodName(...)
            val funMatch = Regex("^(public |internal |private |protected |override |open |inline |suspend )*fun +([A-Za-z0-9_]+) *\\((.*?)\\)").find(trimmed)
            if (funMatch != null) {
                ensureClassForTopLevel(lineNum)
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

                val cls = currentClassName!!
                val ownerFqn = if (packageName.isNotBlank()) "$packageName.$cls" else cls
                val paramsStr = funMatch.groupValues[3]
                parseParams(paramsStr, propertyTypeMap, imports, ownerFqn, funName, lineNum, edges)
                continue
            }

            // Properties
            val propMatch = Regex("^(public |internal |private |protected )*(val|var) +([A-Za-z0-9_]+) *: *([A-Za-z0-9_<>]+)").find(trimmed)
            if (propMatch != null) {
                ensureClassForTopLevel(lineNum)
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

            // Check calls within body: property.<method>(...) or property.<method> { ... } or Type.<method>(...)
            val cls = currentClassName
            if (cls != null) {
                val ownerFqn = if (packageName.isNotBlank()) "$packageName.$cls" else cls

                for ((propName, propType) in propertyTypeMap) {
                    if (Regex("\\b$propName\\.[A-Za-z0-9_]+\\s*(\\(|\\{)").containsMatchIn(trimmed)) {
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

    private fun parseParams(
        paramsStr: String,
        propertyTypeMap: MutableMap<String, String>,
        imports: List<String>,
        ownerFqn: String,
        funName: String,
        lineNum: Int,
        edges: MutableList<DependencyEdge>,
    ) {
        if (paramsStr.isBlank()) return
        val params = paramsStr.split(",")
        for (p in params) {
            val parts = p.trim().split(":")
            if (parts.size == 2) {
                val pName = parts[0].trim().substringAfterLast(" ")
                val pType = parts[1].trim().substringBefore("<").substringBefore("=")
                propertyTypeMap[pName] = pType
                val targetFqn = imports.firstOrNull { it.endsWith(".$pType") }
                if (targetFqn != null) {
                    edges += DependencyEdge(
                        sourceFqn = ownerFqn,
                        targetFqn = targetFqn,
                        kind = EdgeKind.USES,
                        confidence = Confidence.CONFIRMED,
                        line = lineNum,
                        callerMember = funName,
                    )
                }
            }
        }
    }
}
