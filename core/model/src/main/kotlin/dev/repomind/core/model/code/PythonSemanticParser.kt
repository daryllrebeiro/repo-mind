package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.Visibility
import java.nio.file.Files
import java.nio.file.Path

/**
 * Polyglot semantic parser for Python (.py) source files.
 * Extracts classes, methods, constructors (__init__), standalone functions, decorators,
 * inheritance hierarchies (extends), type annotations (uses), and method invocations (calls).
 */
class PythonSemanticParser : LanguageParser {

    override val languageId: String = "python"
    override val supportedExtensions: Set<String> = setOf("py")

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
        val modulePath = relPath.substringBeforeLast('.').replace('/', '.')
        val filePackage = if ('.' in modulePath) modulePath.substringBeforeLast('.') else ""

        val imports = mutableMapOf<String, String>() // imported symbol name -> full module
        val rawImports = mutableListOf<String>()

        // 1. First pass: extract import statements
        for (line in lines) {
            val trimmed = line.trim()
            // e.g. from app.services.order import OrderService, OrderValidator
            val fromImportMatch = Regex("^from\\s+([A-Za-z0-9_.]+)\\s+import\\s+(.+)").find(trimmed)
            if (fromImportMatch != null) {
                val fromPkg = fromImportMatch.groupValues[1].trim()
                rawImports += fromPkg
                val symbols = fromImportMatch.groupValues[2].split(",").map { it.trim().substringBefore(" as ").trim() }
                for (sym in symbols) {
                    if (sym.isNotBlank()) {
                        imports[sym] = "$fromPkg.$sym"
                    }
                }
                continue
            }
            // e.g. import requests or import numpy as np
            val directImportMatch = Regex("^import\\s+([A-Za-z0-9_.]+)").find(trimmed)
            if (directImportMatch != null) {
                val mod = directImportMatch.groupValues[1].trim()
                rawImports += mod
                imports[mod.substringAfterLast('.')] = mod
            }
        }

        // 2. Second pass: classes, functions, methods
        var currentClassName: String? = null
        var currentClassLineStart = 1
        val currentMethods = mutableListOf<ParsedMethod>()
        val currentFields = mutableListOf<ParsedField>()
        val currentAnnotations = mutableListOf<String>()
        val pendingDecorators = mutableListOf<String>()
        var superTypes = mutableListOf<String>()
        val propertyTypeMap = mutableMapOf<String, String>()
        var currentFunName: String? = null

        fun ensureFileClass(startLine: Int) {
            if (currentClassName == null) {
                val fileName = filePath.fileName.toString().substringBeforeLast('.')
                currentClassName = "${fileName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}Module"
                currentClassLineStart = startLine
            }
        }

        fun flushClass(endLine: Int) {
            val cls = currentClassName ?: return
            val fqn = if (filePackage.isNotBlank()) "$filePackage.$cls" else cls

            types += ParsedType(
                fqn = fqn,
                kind = TypeKind.CLASS,
                packageName = filePackage,
                filePath = relPath,
                lineStart = currentClassLineStart,
                lineEnd = endLine,
                annotations = currentAnnotations.toList(),
                superTypeFqn = superTypes.firstOrNull(),
                interfaceFqns = if (superTypes.size > 1) superTypes.drop(1) else emptyList(),
                methods = currentMethods.toList(),
                fields = currentFields.toList(),
                isTest = isTest || cls.startsWith("Test") || cls.endsWith("Test") || relPath.contains("test_") || relPath.contains("_test.py"),
            )

            // Imports as edges
            for (rawImp in rawImports) {
                edges += DependencyEdge(fqn, rawImp, EdgeKind.IMPORTS, Confidence.CONFIRMED, 1)
            }

            currentClassName = null
            currentMethods.clear()
            currentFields.clear()
            currentAnnotations.clear()
            superTypes.clear()
            propertyTypeMap.clear()
            currentFunName = null
        }

        for ((idx, line) in lines.withIndex()) {
            val lineNum = idx + 1
            val trimmed = line.trim()

            // Decorators e.g. @app.get('/users') or @dataclass
            if (trimmed.startsWith("@")) {
                val decName = trimmed.substringAfter('@').substringBefore('(').substringBefore(' ').trim()
                pendingDecorators += decName
                continue
            }

            // Class declaration: class OrderService(BaseService, IValidator):
            val classMatch = Regex("^class\\s+([A-Za-z0-9_]+)(?:\\((.*?)\\))?\\s*:").find(trimmed)
            if (classMatch != null) {
                if (currentClassName != null) {
                    flushClass(lineNum - 1)
                }
                val name = classMatch.groupValues[1]
                val basesStr = classMatch.groupValues[2]

                currentClassName = name
                currentClassLineStart = lineNum
                currentAnnotations.addAll(pendingDecorators)
                pendingDecorators.clear()

                val ownerFqn = if (filePackage.isNotBlank()) "$filePackage.$name" else name

                if (basesStr.isNotBlank()) {
                    val bases = basesStr.split(",").map { it.trim() }
                    for (base in bases) {
                        if (base.isNotBlank() && base != "object") {
                            superTypes += base
                            val targetFqn = imports[base] ?: base
                            edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.EXTENDS, Confidence.CONFIRMED, lineNum)
                        }
                    }
                }
                continue
            }

            // Function/Method declaration: def method_name(self, repo: OrderRepo, ...) -> ReturnType:
            val defMatch = Regex("^def\\s+([A-Za-z0-9_]+)\\s*\\((.*?)\\)(?:\\s*->\\s*([^:]+))?\\s*:").find(trimmed)
            if (defMatch != null) {
                val funName = defMatch.groupValues[1]
                val paramsStr = defMatch.groupValues[2]
                val returnType = defMatch.groupValues[3].takeIf { it.isNotBlank() }
                currentFunName = funName

                val isMethod = line.startsWith("    ") || line.startsWith("\t")
                if (!isMethod && currentClassName != null) {
                    flushClass(lineNum - 1)
                }

                if (!isMethod) {
                    ensureFileClass(lineNum)
                }

                val vis = if (funName.startsWith("_") && !funName.startsWith("__")) Visibility.PRIVATE else Visibility.PUBLIC

                currentMethods += ParsedMethod(
                    name = funName,
                    signature = "$funName()",
                    visibility = vis,
                    isStatic = pendingDecorators.contains("staticmethod"),
                    isAbstract = false,
                    line = lineNum,
                    annotations = pendingDecorators.toList(),
                    returnType = returnType,
                )
                pendingDecorators.clear()

                val cls = currentClassName!!
                val ownerFqn = if (filePackage.isNotBlank()) "$filePackage.$cls" else cls

                // Parse typed parameters: name: Type
                val paramRegex = Regex("([A-Za-z0-9_]+)\\s*:\\s*([A-Za-z0-9_]+)").findAll(paramsStr)
                for (p in paramRegex) {
                    val pName = p.groupValues[1]
                    val pType = p.groupValues[2]
                    if (funName == "__init__" && pName != "self" && pName != "cls") {
                        propertyTypeMap[pName] = pType
                        currentFields += ParsedField(
                            name = pName,
                            declaredType = pType,
                            visibility = Visibility.PRIVATE,
                            isStatic = false,
                            annotations = emptyList(),
                            line = lineNum,
                        )
                    }
                    val targetFqn = imports[pType] ?: if (filePackage.isNotBlank()) "$filePackage.$pType" else null
                    if (targetFqn != null) {
                        edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.USES, Confidence.CONFIRMED, lineNum, funName)
                    }
                }
                continue
            }

            // Method call extraction within body: self.repo.find(...) or service.call(...)
            val cls = currentClassName
            if (cls != null) {
                val ownerFqn = if (filePackage.isNotBlank()) "$filePackage.$cls" else cls
                for ((propName, propType) in propertyTypeMap) {
                    if (Regex("(?:self\\.)?$propName\\.[A-Za-z0-9_]+\\s*\\(").containsMatchIn(trimmed)) {
                        val targetFqn = imports[propType] ?: propType
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
            }
        }

        if (currentClassName != null) {
            flushClass(lines.size)
        }
    }
}
