package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.Visibility
import java.nio.file.Files
import java.nio.file.Path

/**
 * Polyglot semantic parser for TypeScript and JavaScript (.ts, .tsx, .js, .jsx) source files.
 * Extracts classes, interfaces, methods, constructors, standalone functions, imports,
 * class inheritance (extends/implements), dependency injection properties, and call edges.
 */
class TypeScriptSemanticParser : LanguageParser {

    override val languageId: String = "typescript"
    override val supportedExtensions: Set<String> = setOf("ts", "tsx", "js", "jsx")

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
        val modulePath = relPath.substringBeforeLast('.')
        val filePackage = modulePath.substringBeforeLast('/', "").replace('/', '.')

        val imports = mutableMapOf<String, String>() // imported symbol name -> source module
        val rawImports = mutableListOf<String>()

        // 1. First pass: extract import statements
        for (line in lines) {
            val trimmed = line.trim()
            // e.g. import { UserService, UserDto } from './user.service';
            val namedImportMatch = Regex("^import\\s*\\{(.+?)\\}\\s*from\\s*['\"](.+?)['\"]").find(trimmed)
            if (namedImportMatch != null) {
                val symbols = namedImportMatch.groupValues[1].split(",").map { it.trim().substringBefore(" as ").trim() }
                val fromModule = namedImportMatch.groupValues[2].trim()
                rawImports += fromModule
                for (sym in symbols) {
                    if (sym.isNotBlank()) imports[sym] = fromModule
                }
                continue
            }
            // e.g. import * as auth from './auth'; or import UserService from './user.service';
            val defaultImportMatch = Regex("^import\\s+(?:\\*\\s+as\\s+([A-Za-z0-9_]+)|([A-Za-z0-9_]+))\\s+from\\s*['\"](.+?)['\"]").find(trimmed)
            if (defaultImportMatch != null) {
                val alias = defaultImportMatch.groupValues[1].ifEmpty { defaultImportMatch.groupValues[2] }
                val fromModule = defaultImportMatch.groupValues[3].trim()
                rawImports += fromModule
                if (alias.isNotBlank()) imports[alias] = fromModule
            }
        }

        // 2. Second pass: extract classes, interfaces, functions, methods
        var currentClassName: String? = null
        var currentClassLineStart = 1
        var currentClassKind = TypeKind.CLASS
        val currentMethods = mutableListOf<ParsedMethod>()
        val currentFields = mutableListOf<ParsedField>()
        val currentAnnotations = mutableListOf<String>()
        val pendingDecorators = mutableListOf<String>()
        var superTypes = mutableListOf<String>()
        val propertyTypeMap = mutableMapOf<String, String>()
        var currentFunName: String? = null

        fun ensureFileClass(startLine: Int) {
            if (currentClassName == null) {
                val base = filePath.fileName.toString().substringBeforeLast('.')
                currentClassName = "${base.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}Module"
                currentClassLineStart = startLine
                currentClassKind = TypeKind.CLASS
            }
        }

        fun flushClass(endLine: Int) {
            val cls = currentClassName ?: return
            val fqn = if (filePackage.isNotBlank()) "$filePackage.$cls" else cls

            types += ParsedType(
                fqn = fqn,
                kind = currentClassKind,
                packageName = filePackage,
                filePath = relPath,
                lineStart = currentClassLineStart,
                lineEnd = endLine,
                annotations = currentAnnotations.toList(),
                superTypeFqn = superTypes.firstOrNull(),
                interfaceFqns = if (superTypes.size > 1) superTypes.drop(1) else emptyList(),
                methods = currentMethods.toList(),
                fields = currentFields.toList(),
                isTest = isTest || cls.contains("Test") || cls.contains("Spec") || relPath.contains(".test.") || relPath.contains(".spec."),
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

            // Decorators e.g. @Controller('/api') or @Injectable()
            if (trimmed.startsWith("@") && !trimmed.contains("import ")) {
                val decName = trimmed.substringAfter('@').substringBefore('(').substringBefore(' ').trim()
                pendingDecorators += decName
                continue
            }

            // Class and Interface declarations
            val classMatch = Regex("^(?:export\\s+)?(?:abstract\\s+)?(class|interface)\\s+([A-Za-z0-9_]+)(?:\\s+extends\\s+([A-Za-z0-9_]+))?(?:\\s+implements\\s+([A-Za-z0-9_,\\s]+))?").find(trimmed)
            if (classMatch != null) {
                if (currentClassName != null) {
                    flushClass(lineNum - 1)
                }
                val kindStr = classMatch.groupValues[1]
                val name = classMatch.groupValues[2]
                val extendsName = classMatch.groupValues[3].takeIf { it.isNotBlank() }
                val implementsNames = classMatch.groupValues[4].takeIf { it.isNotBlank() }

                currentClassName = name
                currentClassLineStart = lineNum
                currentClassKind = if (kindStr == "interface") TypeKind.INTERFACE else TypeKind.CLASS
                currentAnnotations.addAll(pendingDecorators)
                pendingDecorators.clear()

                val ownerFqn = if (filePackage.isNotBlank()) "$filePackage.$name" else name

                if (extendsName != null) {
                    superTypes += extendsName
                    val targetFqn = imports[extendsName] ?: extendsName
                    edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.EXTENDS, Confidence.CONFIRMED, lineNum)
                }
                if (implementsNames != null) {
                    for (iface in implementsNames.split(",")) {
                        val cleanIface = iface.trim()
                        if (cleanIface.isNotBlank()) {
                            superTypes += cleanIface
                            val targetFqn = imports[cleanIface] ?: cleanIface
                            edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.IMPLEMENTS, Confidence.CONFIRMED, lineNum)
                        }
                    }
                }
                continue
            }

            // Constructors: constructor(private readonly userService: UserService, ...)
            val ctorMatch = Regex("constructor\\s*\\((.*?)\\)").find(trimmed)
            if (ctorMatch != null) {
                val paramsStr = ctorMatch.groupValues[1]
                val ownerFqn = currentClassName?.let { if (filePackage.isNotBlank()) "$filePackage.$it" else it } ?: ""
                val paramDeclRegex = Regex("(?:private|protected|public)?\\s*(?:readonly)?\\s*([A-Za-z0-9_]+)\\s*:\\s*([A-Za-z0-9_<>]+)").findAll(paramsStr)
                for (p in paramDeclRegex) {
                    val pName = p.groupValues[1]
                    val pType = p.groupValues[2].substringBefore('<')
                    propertyTypeMap[pName] = pType
                    currentFields += ParsedField(
                        name = pName,
                        declaredType = pType,
                        visibility = Visibility.PRIVATE,
                        isStatic = false,
                        annotations = emptyList(),
                        line = lineNum,
                    )
                    val targetFqn = imports[pType] ?: pType
                    edges += DependencyEdge(ownerFqn, targetFqn, EdgeKind.USES, Confidence.CONFIRMED, lineNum)
                }
                continue
            }

            // Methods: async methodName(...) or methodName(...)
            val methodMatch = Regex("^(?:public|private|protected)?\\s*(?:async\\s+)?([A-Za-z0-9_]+)\\s*\\((.*?)\\)\\s*(?::\\s*([A-Za-z0-9_<>]+))?\\s*\\{?").find(trimmed)
            if (methodMatch != null && !trimmed.startsWith("if ") && !trimmed.startsWith("for ") && !trimmed.startsWith("while ") && !trimmed.startsWith("switch ")) {
                val funName = methodMatch.groupValues[1]
                val paramsStr = methodMatch.groupValues[2]
                val returnType = methodMatch.groupValues[3].takeIf { it.isNotBlank() }
                currentFunName = funName

                val isPrivate = trimmed.startsWith("private ")
                val vis = if (isPrivate) Visibility.PRIVATE else Visibility.PUBLIC

                currentMethods += ParsedMethod(
                    name = funName,
                    signature = "$funName()",
                    visibility = vis,
                    isStatic = "static " in trimmed,
                    isAbstract = "abstract " in trimmed || currentClassKind == TypeKind.INTERFACE,
                    line = lineNum,
                    annotations = pendingDecorators.toList(),
                    returnType = returnType,
                )
                pendingDecorators.clear()

                val ownerFqn = currentClassName?.let { if (filePackage.isNotBlank()) "$filePackage.$it" else it } ?: ""
                // Parse params
                for (p in paramsStr.split(",")) {
                    val parts = p.trim().split(":")
                    if (parts.size == 2) {
                        val pType = parts[1].trim().substringBefore('<').substringBefore('=').trim()
                        if (pType in imports) {
                            edges += DependencyEdge(ownerFqn, imports[pType]!!, EdgeKind.USES, Confidence.CONFIRMED, lineNum, funName)
                        }
                    }
                }
                continue
            }

            // Standalone functions: function funcName(...) or export const funcName = (...) =>
            val funcMatch = Regex("^(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z0-9_]+)\\s*\\((.*?)\\)").find(trimmed)
                ?: Regex("^(?:export\\s+)?const\\s+([A-Za-z0-9_]+)\\s*=\\s*(?:async\\s+)?\\((.*?)\\)\\s*=>").find(trimmed)
            if (funcMatch != null) {
                ensureFileClass(lineNum)
                val funName = funcMatch.groupValues[1]
                currentFunName = funName

                currentMethods += ParsedMethod(
                    name = funName,
                    signature = "$funName()",
                    visibility = Visibility.PUBLIC,
                    isStatic = false,
                    isAbstract = false,
                    line = lineNum,
                    annotations = pendingDecorators.toList(),
                )
                pendingDecorators.clear()
                continue
            }

            // Method/function call extraction within body: this.property.method(...) or importedFunc(...)
            val cls = currentClassName
            if (cls != null) {
                val ownerFqn = if (filePackage.isNotBlank()) "$filePackage.$cls" else cls
                for ((propName, propType) in propertyTypeMap) {
                    if (Regex("(?:this\\.)?$propName\\.[A-Za-z0-9_]+\\s*\\(").containsMatchIn(trimmed)) {
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
