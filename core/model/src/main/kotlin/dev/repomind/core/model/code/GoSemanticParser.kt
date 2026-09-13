package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.Visibility
import java.nio.file.Files
import java.nio.file.Path

/**
 * Polyglot semantic parser for Go (.go) source files.
 * Extracts Go packages, structs, interfaces, functions, receiver methods,
 * imports, struct field types, and inter-package / inter-type call edges.
 */
class GoSemanticParser : LanguageParser {

    override val languageId: String = "go"
    override val supportedExtensions: Set<String> = setOf("go")

    override fun parseModule(module: RepoModule, classpath: List<Path>): ModuleParse {
        val types = mutableListOf<ParsedType>()
        val edges = mutableListOf<DependencyEdge>()

        for (sourceRoot in module.sourceRoots) {
            val rootPath = sourceRoot.path
            if (!Files.isDirectory(rootPath)) continue

            rootPath.toFile().walkTopDown()
                .filter { it.isFile && it.extension in supportedExtensions }
                .forEach { file ->
                    parseFile(module.name, rootPath, file.toPath(), sourceRoot.isTest, types, edges)
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
        moduleName: String,
        sourceRoot: Path,
        filePath: Path,
        isTest: Boolean,
        types: MutableList<ParsedType>,
        edges: MutableList<DependencyEdge>,
    ) {
        val lines = Files.readAllLines(filePath)
        val relPath = sourceRoot.relativize(filePath).toString().replace('\\', '/')
        val fileName = filePath.fileName.toString().removeSuffix(".go")
        val fileIsTest = isTest || fileName.endsWith("_test")

        var packageName = ""
        val imports = mutableMapOf<String, String>() // alias/pkgName -> full import path
        val rawImports = mutableListOf<String>()

        var inImportBlock = false

        // Pass 1: Package declaration and imports
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue

            if (trimmed.startsWith("package ")) {
                packageName = trimmed.removePrefix("package ").substringBefore("//").substringBefore("/*").trim()
            } else if (trimmed == "import (") {
                inImportBlock = true
            } else if (inImportBlock) {
                if (trimmed == ")") {
                    inImportBlock = false
                } else if (trimmed.isNotBlank()) {
                    parseImportLine(trimmed, imports, rawImports)
                }
            } else if (trimmed.startsWith("import ")) {
                val importContent = trimmed.removePrefix("import ").trim()
                parseImportLine(importContent, imports, rawImports)
            }
        }

        val pkgPrefix = if (packageName.isNotBlank()) packageName else "main"

        // Pass 2: Types, structs, interfaces, methods, and functions
        val fileTypes = mutableMapOf<String, ParsedType>()
        val standaloneMethods = mutableListOf<ParsedMethod>()

        var currentOwnerFqn: String? = null
        var currentMemberName: String? = null
        val scopedVariables = mutableMapOf<String, String>()

        for ((idx, line) in lines.withIndex()) {
            val lineNum = idx + 1
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue

            // Struct declaration: type OrderService struct {
            val structMatch = Regex("""^type\s+([A-Za-z0-9_]+)\s+struct\s*\{""").find(trimmed)
            if (structMatch != null) {
                val name = structMatch.groupValues[1]
                val fqn = "$pkgPrefix.$name"
                fileTypes[name] = ParsedType(
                    fqn = fqn,
                    kind = TypeKind.CLASS,
                    packageName = pkgPrefix,
                    filePath = relPath,
                    lineStart = lineNum,
                    lineEnd = lineNum,
                    annotations = emptyList(),
                    superTypeFqn = null,
                    interfaceFqns = emptyList(),
                    methods = emptyList(),
                    fields = emptyList(),
                    isTest = fileIsTest,
                )
                currentOwnerFqn = fqn
                currentMemberName = null
                scopedVariables.clear()
                continue
            }

            // Interface declaration: type OrderRepository interface {
            val interfaceMatch = Regex("""^type\s+([A-Za-z0-9_]+)\s+interface\s*\{""").find(trimmed)
            if (interfaceMatch != null) {
                val name = interfaceMatch.groupValues[1]
                val fqn = "$pkgPrefix.$name"
                fileTypes[name] = ParsedType(
                    fqn = fqn,
                    kind = TypeKind.INTERFACE,
                    packageName = pkgPrefix,
                    filePath = relPath,
                    lineStart = lineNum,
                    lineEnd = lineNum,
                    annotations = emptyList(),
                    superTypeFqn = null,
                    interfaceFqns = emptyList(),
                    methods = emptyList(),
                    fields = emptyList(),
                    isTest = fileIsTest,
                )
                currentOwnerFqn = fqn
                currentMemberName = null
                scopedVariables.clear()
                continue
            }

            // Receiver Method: func (s *OrderService) ProcessOrder(id string) error {
            val methodMatch = Regex("""^func\s+\(\s*([A-Za-z0-9_]+)\s+\*?([A-Za-z0-9_]+)\s*\)\s+([A-Za-z0-9_]+)\s*\((.*?)\)""").find(trimmed)
            if (methodMatch != null) {
                val recvVar = methodMatch.groupValues[1]
                val recvType = methodMatch.groupValues[2]
                val methodName = methodMatch.groupValues[3]
                val paramsStr = methodMatch.groupValues[4]
                val methodVis = if (methodName.firstOrNull()?.isUpperCase() == true) Visibility.PUBLIC else Visibility.PACKAGE

                val method = ParsedMethod(
                    name = methodName,
                    signature = "$methodName($paramsStr)",
                    visibility = methodVis,
                    isStatic = false,
                    isAbstract = false,
                    line = lineNum,
                )

                val ownerFqn = "$pkgPrefix.$recvType"
                if (!fileTypes.containsKey(recvType)) {
                    fileTypes[recvType] = ParsedType(
                        fqn = ownerFqn,
                        kind = TypeKind.CLASS,
                        packageName = pkgPrefix,
                        filePath = relPath,
                        lineStart = lineNum,
                        lineEnd = lineNum,
                        annotations = emptyList(),
                        superTypeFqn = null,
                        interfaceFqns = emptyList(),
                        methods = emptyList(),
                        fields = emptyList(),
                        isTest = fileIsTest,
                    )
                }

                val existing = fileTypes[recvType]!!
                fileTypes[recvType] = existing.copy(methods = existing.methods + method)

                currentOwnerFqn = ownerFqn
                currentMemberName = methodName
                scopedVariables.clear()
                scopedVariables[recvVar] = recvType
                scopedVariables.putAll(extractParamPairs(paramsStr))

                // USES edges for parameters
                val paramTypes = extractParamTypes(paramsStr)
                for (cleanType in paramTypes) {
                    if (cleanType.isNotBlank() && !isGoBuiltin(cleanType)) {
                        val targetFqn = resolveTypeFqn(cleanType, pkgPrefix, imports)
                        edges += DependencyEdge(
                            sourceFqn = ownerFqn,
                            targetFqn = targetFqn,
                            kind = EdgeKind.USES,
                            confidence = Confidence.CONFIRMED,
                            line = lineNum,
                            callerMember = methodName,
                        )
                    }
                }
                continue
            }

            // Standalone function: func CalculateTax(amount float64) float64 {
            val funcMatch = Regex("""^func\s+([A-Za-z0-9_]+)\s*\((.*?)\)""").find(trimmed)
            if (funcMatch != null) {
                val funcName = funcMatch.groupValues[1]
                val paramsStr = funcMatch.groupValues[2]
                val funcVis = if (funcName.firstOrNull()?.isUpperCase() == true) Visibility.PUBLIC else Visibility.PACKAGE

                val method = ParsedMethod(
                    name = funcName,
                    signature = "$funcName($paramsStr)",
                    visibility = funcVis,
                    isStatic = true,
                    isAbstract = false,
                    line = lineNum,
                )
                standaloneMethods += method

                val packageTypeFqn = "$pkgPrefix.${fileName.replaceFirstChar { it.uppercase() }}Pkg"
                currentOwnerFqn = packageTypeFqn
                currentMemberName = funcName
                scopedVariables.clear()
                scopedVariables.putAll(extractParamPairs(paramsStr))

                val paramTypes = extractParamTypes(paramsStr)
                for (cleanType in paramTypes) {
                    if (cleanType.isNotBlank() && !isGoBuiltin(cleanType)) {
                        val targetFqn = resolveTypeFqn(cleanType, pkgPrefix, imports)
                        edges += DependencyEdge(
                            sourceFqn = packageTypeFqn,
                            targetFqn = targetFqn,
                            kind = EdgeKind.USES,
                            confidence = Confidence.CONFIRMED,
                            line = lineNum,
                            callerMember = funcName,
                        )
                    }
                }
                continue
            }

            // Call expression pattern: receiver.Method(...) or pkg.Func(...)
            val callMatches = Regex("""([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)\s*\(""").findAll(trimmed)
            for (callMatch in callMatches) {
                val calleeOwner = callMatch.groupValues[1]
                val calleeMethod = callMatch.groupValues[2]

                val sourceOwner = currentOwnerFqn ?: "$pkgPrefix.${fileName.replaceFirstChar { it.uppercase() }}Pkg"
                val callerMember = currentMemberName

                if (imports.containsKey(calleeOwner)) {
                    val importPath = imports[calleeOwner]!!
                    val targetFqn = "$importPath.$calleeMethod"
                    edges += DependencyEdge(
                        sourceFqn = sourceOwner,
                        targetFqn = targetFqn,
                        kind = EdgeKind.CALLS,
                        confidence = Confidence.CONFIRMED,
                        line = lineNum,
                        callerMember = callerMember,
                    )
                } else if (scopedVariables.containsKey(calleeOwner)) {
                    val varType = scopedVariables[calleeOwner]!!
                    val pkgName = varType.substringBefore('.')
                    if (imports.containsKey(pkgName)) {
                        val importPath = imports[pkgName]!!
                        val targetFqn = "$importPath.$calleeMethod"
                        edges += DependencyEdge(
                            sourceFqn = sourceOwner,
                            targetFqn = targetFqn,
                            kind = EdgeKind.CALLS,
                            confidence = Confidence.CONFIRMED,
                            line = lineNum,
                            callerMember = callerMember,
                        )
                    } else {
                        val targetFqn = "$pkgPrefix.$varType#$calleeMethod"
                        edges += DependencyEdge(
                            sourceFqn = sourceOwner,
                            targetFqn = targetFqn,
                            kind = EdgeKind.CALLS,
                            confidence = Confidence.POSSIBLE,
                            line = lineNum,
                            callerMember = callerMember,
                        )
                    }
                } else if (!isGoBuiltin(calleeOwner)) {
                    val targetFqn = "$pkgPrefix.$calleeOwner#$calleeMethod"
                    edges += DependencyEdge(
                        sourceFqn = sourceOwner,
                        targetFqn = targetFqn,
                        kind = EdgeKind.CALLS,
                        confidence = Confidence.POSSIBLE,
                        line = lineNum,
                        callerMember = callerMember,
                    )
                }
            }
        }

        // Register package type for standalone functions if any
        if (standaloneMethods.isNotEmpty()) {
            val pkgTypeName = "${fileName.replaceFirstChar { it.uppercase() }}Pkg"
            val fqn = "$pkgPrefix.$pkgTypeName"
            types += ParsedType(
                fqn = fqn,
                kind = TypeKind.CLASS,
                packageName = pkgPrefix,
                filePath = relPath,
                lineStart = 1,
                lineEnd = lines.size,
                annotations = emptyList(),
                superTypeFqn = null,
                interfaceFqns = emptyList(),
                methods = standaloneMethods,
                fields = emptyList(),
                isTest = fileIsTest,
            )
        }

        // Add file types and import edges
        for (parsedType in fileTypes.values) {
            types += parsedType
            for (rawImp in rawImports) {
                edges += DependencyEdge(
                    sourceFqn = parsedType.fqn,
                    targetFqn = rawImp,
                    kind = EdgeKind.IMPORTS,
                    confidence = Confidence.CONFIRMED,
                    line = 1,
                )
            }
        }
    }

    private fun parseImportLine(line: String, imports: MutableMap<String, String>, rawImports: MutableList<String>) {
        val trimmed = line.trim()
        val quoteMatch = Regex("""^(?:([A-Za-z0-9_]+)\s+)?"([^"]+)"""").find(trimmed)
        if (quoteMatch != null) {
            val alias = quoteMatch.groupValues[1].ifBlank { null }
            val path = quoteMatch.groupValues[2]
            rawImports += path
            val pkgName = alias ?: path.substringAfterLast('/')
            imports[pkgName] = path
        }
    }

    private fun extractParamTypes(paramsStr: String): List<String> {
        if (paramsStr.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        val parts = paramsStr.split(',')
        for (part in parts) {
            val tokens = part.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.size >= 2) {
                result += tokens[1].removePrefix("*").removePrefix("[]")
            } else if (tokens.size == 1) {
                result += tokens[0].removePrefix("*").removePrefix("[]")
            }
        }
        return result
    }

    private fun extractParamPairs(paramsStr: String): Map<String, String> {
        if (paramsStr.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        val parts = paramsStr.split(',')
        for (part in parts) {
            val tokens = part.trim().split(Regex("""\s+""")).filter { it.isNotBlank() }
            if (tokens.size >= 2) {
                val varName = tokens[0]
                val typeName = tokens[1].removePrefix("*").removePrefix("[]")
                map[varName] = typeName
            }
        }
        return map
    }

    private fun resolveTypeFqn(type: String, currentPkg: String, imports: Map<String, String>): String {
        return if (type.contains('.')) {
            val pkg = type.substringBefore('.')
            val name = type.substringAfter('.')
            val fullPkg = imports[pkg] ?: pkg
            "$fullPkg.$name"
        } else {
            "$currentPkg.$type"
        }
    }

    private fun isGoBuiltin(name: String): Boolean {
        return name in setOf(
            "string", "int", "int8", "int16", "int32", "int64",
            "uint", "uint8", "uint16", "uint32", "uint64",
            "float32", "float64", "bool", "byte", "rune", "error",
            "len", "cap", "make", "new", "append", "copy", "close",
            "panic", "recover", "print", "println", "fmt", "log", "time",
        )
    }
}
