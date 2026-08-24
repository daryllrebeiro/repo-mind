package dev.repomind.language.java

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
import com.github.javaparser.resolution.UnsolvedSymbolException
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.core.model.Visibility
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.model.code.ModuleParse
import dev.repomind.core.model.code.ParsedField
import dev.repomind.core.model.code.ParsedMethod
import dev.repomind.core.model.code.ParsedType
import dev.repomind.core.model.code.TypeKind
import dev.repomind.core.model.code.UnresolvedSymbol
import java.nio.file.Path

class JavaSemanticParser {

    fun parseModule(module: RepoModule, classpathJars: List<Path>): ModuleParse {
        val config = ParserConfiguration()
        val typeSolver = buildTypeSolver(module.sourceRoots, classpathJars)
        config.setSymbolResolver(JavaSymbolSolver(typeSolver))
        StaticJavaParser.setConfiguration(config)
        JavaParserFacade.clearInstances()

        val types = mutableListOf<ParsedType>()
        val unresolved = mutableListOf<UnresolvedSymbol>()
        val importsByType = mutableMapOf<String, List<Pair<String, Int>>>()
        val cusByType = mutableMapOf<String, Pair<com.github.javaparser.ast.CompilationUnit, com.github.javaparser.ast.body.TypeDeclaration<*>>>()
        val filesByType = mutableMapOf<String, Path>()

        for (sourceRoot in module.sourceRoots) {
            for (file in listJavaFiles(sourceRoot.path)) {
                try {
                    val cu = StaticJavaParser.parse(file)
                    val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                    val imports = cu.imports
                        .filter { !it.isStatic && !it.isAsterisk }
                        .map { it.name.asString() to it.range.map { r -> r.begin.line }.orElse(0) }
                    for (typeDecl in cu.types) {
                        extractType(typeDecl, pkg, file, unresolved, sourceRoot.isTest)?.let { parsed ->
                            types += parsed
                            if (imports.isNotEmpty()) {
                                importsByType[parsed.fqn] = imports
                            }
                            cusByType[parsed.fqn] = cu to typeDecl
                            filesByType[parsed.fqn] = file
                        }
                    }
                } catch (e: Exception) {
                    unresolved += UnresolvedSymbol(
                        symbol = "<parse-error: ${e.javaClass.simpleName}>",
                        filePath = file.toString(),
                        line = 0,
                    )
                }
            }
        }

        val edges = buildEdges(types, importsByType).toMutableList()
        edges += extractCalls(typeSolver, types, cusByType, filesByType)

        return ModuleParse(
            moduleName = module.name,
            types = types,
            unresolvedSymbols = unresolved.distinctBy { Triple(it.symbol, it.filePath, it.line) },
            edges = edges,
        )
    }

    private fun extractCalls(
        typeSolver: CombinedTypeSolver,
        types: List<ParsedType>,
        cusByType: Map<String, Pair<com.github.javaparser.ast.CompilationUnit, com.github.javaparser.ast.body.TypeDeclaration<*>>>,
        filesByType: Map<String, Path>,
    ): List<DependencyEdge> {
        val projectTypes = types.map { it.fqn }.toSet()
        val implsByInterface = buildMap<String, MutableList<String>> {
            for (type in types) {
                for (iface in type.interfaceFqns) {
                    getOrPut(iface) { mutableListOf() }.add(type.fqn)
                }
            }
        }
        val fieldsByTypeAndName = types.associate { t ->
            t.fqn to t.fields.associate { it.name to it.declaredType.substringBefore('<').substringAfterLast('.') }
        }

        val calls = mutableListOf<DependencyEdge>()
        val facade = JavaParserFacade.get(typeSolver)

        for ((fqn, pair) in cusByType) {
            val (cu, typeDecl) = pair
            val file = filesByType[fqn] ?: continue

            for (call in typeDecl.findAll(com.github.javaparser.ast.expr.MethodCallExpr::class.java)) {
                val line = call.range.map { it.begin.line }.orElse(0)
                try {
                    val declaring = call.resolve().declaringType()
                    val declaringFqn = declaring.qualifiedName
                    calls += DependencyEdge(fqn, "$declaringFqn#${call.nameAsString}", EdgeKind.CALLS, Confidence.CONFIRMED, line)

                    if (declaring.isInterface && declaringFqn in projectTypes) {
                        val impls = implsByInterface[declaringFqn].orEmpty()
                        if (impls.size == 1) {
                            calls += DependencyEdge(fqn, "${impls.single()}#${call.nameAsString}", EdgeKind.CALLS, Confidence.POSSIBLE, line)
                        }
                    }
                    continue
                } catch (_: Exception) {
                }

                val scopeName = (call.scope.orElse(null) as? com.github.javaparser.ast.expr.NameExpr)?.nameAsString
                    ?: continue
                val fieldTypeFqn = fieldToFqn(fieldsByTypeAndName[fqn]?.get(scopeName), facade) ?: continue
                if (fieldTypeFqn !in projectTypes) continue
                val impls = implsByInterface[fieldTypeFqn].orEmpty()
                when (impls.size) {
                    1 -> calls += DependencyEdge(fqn, "${impls.single()}#${call.nameAsString}", EdgeKind.CALLS, Confidence.POSSIBLE, line)
                    else -> {
                        if (impls.isEmpty()) {
                            calls += DependencyEdge(fqn, "$fieldTypeFqn#${call.nameAsString}", EdgeKind.CALLS, Confidence.POSSIBLE, line)
                        }
                    }
                }
            }

            for (ctor in typeDecl.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr::class.java)) {
                val line = ctor.range.map { it.begin.line }.orElse(0)
                try {
                    val created = facade.solve(ctor).getDeclaration()
                        .map { it.declaringType().qualifiedName }
                        .orElse(null)
                    if (created != null && created in projectTypes) {
                        calls += DependencyEdge(fqn, "$created#<init>", EdgeKind.CALLS, Confidence.CONFIRMED, line)
                    }
                } catch (_: Exception) {
                }
            }
        }

        return calls.distinctBy { e -> listOf(e.sourceFqn, e.targetFqn, e.kind, e.confidence) }
    }

    private fun fieldToFqn(simpleTypeName: String?, facade: JavaParserFacade): String? {
        if (simpleTypeName == null) return null
        return try {
            val solved = facade.typeSolver.tryToSolveType(simpleTypeName)
            if (solved.isSolved) {
                solved.correspondingDeclaration.qualifiedName
            } else {
                simpleTypeName.takeIf { '.' in it }
            }
        } catch (_: Exception) {
            simpleTypeName.takeIf { '.' in it }
        }
    }

    private fun buildEdges(
        types: List<ParsedType>,
        importsByType: Map<String, List<Pair<String, Int>>>,
    ): List<DependencyEdge> {
        val edges = mutableListOf<DependencyEdge>()

        for (type in types) {
            type.superTypeFqn?.let {
                edges += DependencyEdge(type.fqn, it, EdgeKind.EXTENDS, Confidence.CONFIRMED)
            }
            for (iface in type.interfaceFqns) {
                edges += DependencyEdge(type.fqn, iface, EdgeKind.IMPLEMENTS, Confidence.CONFIRMED)
            }
            importsByType[type.fqn]?.forEach { (target, line) ->
                edges += DependencyEdge(type.fqn, target, EdgeKind.IMPORTS, Confidence.CONFIRMED, line)
            }
        }

        val simpleNameIndex = types.groupBy { it.fqn.substringAfterLast('.') }
        for (type in types) {
            for (field in type.fields.filter { !it.synthetic }) {
                val simple = field.declaredType.substringBefore('<').substringAfterLast('.')
                simpleNameIndex[simple]?.singleOrNull()?.let { target ->
                    if (target.fqn != type.fqn) {
                        edges += DependencyEdge(type.fqn, target.fqn, EdgeKind.USES, Confidence.POSSIBLE, field.line)
                    }
                }
            }
        }

        edges += buildTestEdges(types, importsByType, simpleNameIndex)

        return edges.distinctBy { Triple(it.sourceFqn, it.targetFqn, it.kind) }
    }

    private fun buildTestEdges(
        types: List<ParsedType>,
        importsByType: Map<String, List<Pair<String, Int>>>,
        simpleNameIndex: Map<String, List<ParsedType>>,
    ): List<DependencyEdge> {
        val productionFqns = types.filter { !it.isTest }.map { it.fqn }.toSet()
        if (productionFqns.isEmpty()) return emptyList()
        val prodBySimpleName = simpleNameIndex.mapValues { (_, candidates) ->
            candidates.filter { !it.isTest }
        }

        val testEdges = mutableListOf<DependencyEdge>()
        for (test in types.filter { it.isTest }) {
            val confirmed = importsByType[test.fqn].orEmpty()
                .map { it.first }
                .filter { it in productionFqns }

            for (target in confirmed) {
                testEdges += DependencyEdge(test.fqn, target, EdgeKind.TESTS, Confidence.CONFIRMED)
            }

            val referencedSimpleNames = (
                test.fields.filter { !it.synthetic }.map { it.declaredType.substringBefore('<').substringAfterLast('.') } +
                    test.methods.flatMap { m -> m.signature.substringAfter('(').substringBefore(')').split(',') }
                        .map { it.trim().substringBefore('<').substringAfterLast('.') } +
                    test.superTypeFqn?.let { listOf(it.substringAfterLast('.')) }.orEmpty()
                ).filter { it.isNotBlank() && it !in setOf("String", "Integer", "Long", "Boolean", "void", "List", "Map", "Set") }

            for (simple in referencedSimpleNames.toSet()) {
                val candidates = prodBySimpleName[simple].orEmpty()
                val candidate = candidates.singleOrNull()?.fqn ?: continue
                if (candidate == test.fqn || candidate in confirmed) continue
                testEdges += DependencyEdge(test.fqn, candidate, EdgeKind.TESTS, Confidence.POSSIBLE)
            }
        }
        return testEdges
    }

    private fun buildTypeSolver(sourceRoots: List<SourceRoot>, classpathJars: List<Path>): CombinedTypeSolver {
        val solver = CombinedTypeSolver()
        solver.add(ReflectionTypeSolver(true))
        for (root in sourceRoots.filter { !it.isTest }) {
            solver.add(JavaParserTypeSolver(root.path))
        }
        for (jar in classpathJars) {
            try {
                solver.add(JarTypeSolver(jar))
            } catch (_: Exception) {
            }
        }
        return solver
    }

    private fun listJavaFiles(root: Path): List<Path> =
        root.toFile().walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .map { it.toPath() }
            .toList()

    private fun extractType(
        typeDecl: TypeDeclaration<*>,
        pkg: String,
        file: Path,
        unresolved: MutableList<UnresolvedSymbol>,
        isTest: Boolean = false,
    ): ParsedType? {
        val fqn = if (pkg.isEmpty()) typeDecl.nameAsString else "$pkg.${typeDecl.nameAsString}"

        var superFqn: String? = null
        val interfaceNames: List<String>
        if (typeDecl is ClassOrInterfaceDeclaration) {
            if (typeDecl.isInterface) {
                superFqn = typeDecl.extendedTypes.firstOrNull()?.let { resolveTypeName(it, unresolved, file) }
                interfaceNames = emptyList()
            } else {
                superFqn = typeDecl.extendedTypes.firstOrNull()?.let { resolveTypeName(it, unresolved, file) }
                interfaceNames = typeDecl.implementedTypes.mapNotNull { resolveTypeName(it, unresolved, file) }
            }
        } else {
            interfaceNames = emptyList()
        }

        val annotations = typeDecl.annotations.map { it.nameAsString.substringAfterLast('.') }
        val configPrefix = typeDecl.annotations
            .filter { it.nameAsString.substringAfterLast('.') == "ConfigurationProperties" }
            .firstNotNullOfOrNull { annotationValue(it, setOf("prefix", "value")) }
        val methods = buildList {
            addAll(typeDecl.methods.map { m ->
                ParsedMethod(
                    name = m.nameAsString,
                    signature = "${m.nameAsString}(${m.parameters.joinToString(",") { p -> p.type.asString() }})",
                    visibility = visibilityOf(m),
                    isStatic = m.isStatic,
                    isAbstract = m.isAbstract,
                    line = m.range.map { it.begin.line }.orElse(0),
                )
            })
            addAll(LombokSynthesizer.synthesizeMembers(typeDecl))
        }
        val fields = buildList {
            addAll(typeDecl.fields.flatMap { f ->
                f.variables.map { v ->
                    val configKeys = f.annotations
                        .filter { it.nameAsString.substringAfterLast('.') == "Value" }
                        .mapNotNull { annotationValue(it, setOf("value")) }
                        .flatMap { expr -> extractPlaceholderKey(expr)?.let { listOf(it) } ?: emptyList() }
                    ParsedField(
                        name = v.nameAsString,
                        declaredType = v.type.asString(),
                        visibility = visibilityOf(f),
                        isStatic = f.isStatic,
                        annotations = f.annotations.map { it.nameAsString.substringAfterLast('.') },
                        configKeys = configKeys,
                        line = f.range.map { it.begin.line }.orElse(0),
                    )
                }
            })
            addAll(LombokSynthesizer.synthesizeFields(typeDecl))
        }

        return ParsedType(
            fqn = fqn,
            kind = kindOf(typeDecl),
            packageName = pkg,
            filePath = file.toString(),
            lineStart = typeDecl.range.map { it.begin.line }.orElse(0),
            lineEnd = typeDecl.range.map { it.end.line }.orElse(0),
            annotations = annotations,
            configPrefix = configPrefix,
            superTypeFqn = superFqn,
            interfaceFqns = interfaceNames,
            methods = methods,
            fields = fields,
            isTest = isTest,
        )
    }

    private fun annotationValue(annotation: com.github.javaparser.ast.expr.AnnotationExpr, members: Set<String>): String? =
        when (annotation) {
            is com.github.javaparser.ast.expr.SingleMemberAnnotationExpr ->
                if ("value" in members) stripQuotes(annotation.memberValue.toString()) else null
            is com.github.javaparser.ast.expr.NormalAnnotationExpr ->
                annotation.pairs
                    .filter { it.nameAsString in members }
                    .firstOrNull()
                    ?.let { stripQuotes(it.value.toString()) }
            else -> null
        }

    private fun stripQuotes(raw: String): String =
        raw.trim().removeSurrounding("\"").trim()

    private fun extractPlaceholderKey(expression: String): String? {
        val match = Regex("\\$\\{([^}:]+)(:[^}]*)?\\}").find(expression.trim().removeSurrounding("\""))
        return match?.groupValues?.get(1)?.trim()
    }

    private fun resolveTypeName(
        type: ClassOrInterfaceType,
        unresolved: MutableList<UnresolvedSymbol>,
        file: Path,
    ): String? = try {
        (type.resolve() as? com.github.javaparser.resolution.types.ResolvedReferenceType)?.qualifiedName
    } catch (e: UnsolvedSymbolException) {
        unresolved += UnresolvedSymbol(type.asString(), file.toString(), type.range.map { it.begin.line }.orElse(0))
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun kindOf(decl: TypeDeclaration<*>): TypeKind = when (decl) {
        is ClassOrInterfaceDeclaration -> if (decl.isInterface) TypeKind.INTERFACE else TypeKind.CLASS
        is EnumDeclaration -> TypeKind.ENUM
        is RecordDeclaration -> TypeKind.RECORD
        is AnnotationDeclaration -> TypeKind.ANNOTATION
        else -> TypeKind.CLASS
    }

    private fun visibilityOf(node: NodeWithModifiers<*>): Visibility {
        val keywords = node.modifiers.map { it.keyword }
        return when {
            Modifier.Keyword.PUBLIC in keywords -> Visibility.PUBLIC
            Modifier.Keyword.PROTECTED in keywords -> Visibility.PROTECTED
            Modifier.Keyword.PRIVATE in keywords -> Visibility.PRIVATE
            else -> Visibility.PACKAGE
        }
    }
}


