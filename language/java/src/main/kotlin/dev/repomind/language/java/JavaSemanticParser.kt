package dev.repomind.language.java

import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.body.AnnotationDeclaration
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.resolution.UnsolvedSymbolException
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade
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
        val cusByType = mutableMapOf<String, Pair<com.github.javaparser.ast.CompilationUnit, TypeDeclaration<*>>>()
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
                        try {
                            extractType(typeDecl, pkg, file, unresolved, sourceRoot.isTest)?.let { parsed ->
                                types += parsed
                                if (imports.isNotEmpty()) {
                                    importsByType[parsed.fqn] = imports
                                }
                                cusByType[parsed.fqn] = cu to typeDecl
                                filesByType[parsed.fqn] = file
                            }
                        } catch (e: Exception) {
                            unresolved += UnresolvedSymbol(
                                symbol = typeDecl.nameAsString,
                                filePath = file.toString(),
                                line = typeDecl.range.map { r -> r.begin.line }.orElse(0),
                                reason = "${e.javaClass.simpleName}: ${e.message}",
                            )
                        }
                    }
                } catch (e: Exception) {
                    unresolved += UnresolvedSymbol(
                        symbol = "<parse-error: ${e.javaClass.simpleName}>",
                        filePath = file.toString(),
                        line = 0,
                        reason = e.message ?: e.javaClass.name,
                    )
                }
            }
        }

        val edges = buildEdges(types, importsByType).toMutableList()
        edges += extractCalls(typeSolver, types, cusByType, filesByType, unresolved)

        val deduped = edges
            .groupBy { Triple(it.sourceFqn, it.targetFqn, it.kind) }
            .values
            .map { group ->
                group.sortedWith(
                    compareByDescending<DependencyEdge> { it.confidence == Confidence.CONFIRMED }
                        .thenByDescending { it.callerMember != null }
                ).first()
            }

        return ModuleParse(
            moduleName = module.name,
            types = types,
            unresolvedSymbols = unresolved.distinctBy { Triple(it.symbol, it.filePath, it.line) },
            edges = deduped,
        )
    }

    private fun extractCalls(
        typeSolver: CombinedTypeSolver,
        types: List<ParsedType>,
        cusByType: Map<String, Pair<com.github.javaparser.ast.CompilationUnit, TypeDeclaration<*>>>,
        filesByType: Map<String, Path>,
        unresolved: MutableList<UnresolvedSymbol>,
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
        val testTypes = types.filter { it.isTest }.map { it.fqn }.toSet()
        val testCoverageEdges = mutableListOf<DependencyEdge>()

        fun emitCall(sourceClassFqn: String, sourceMethodFqn: String, targetFqn: String, conf: Confidence, line: Int) {
            val member = if (sourceMethodFqn != sourceClassFqn) sourceMethodFqn.substringAfter('#') else null
            calls += DependencyEdge(sourceClassFqn, targetFqn, EdgeKind.CALLS, conf, line, member)
        }

        for ((fqn, pair) in cusByType) {
            val (cu, typeDecl) = pair
            val file = filesByType[fqn] ?: continue
            val qualifiers = extractFieldQualifiers(typeDecl)
            val isTestClass = fqn in testTypes || typeDecl.annotations.any { it.nameAsString.endsWith("Test") }
            val mockedTypes = if (isTestClass) extractMockedTypes(typeDecl, types) else emptySet()

            for (call in typeDecl.findAll(com.github.javaparser.ast.expr.MethodCallExpr::class.java)) {
                val line = call.range.map { it.begin.line }.orElse(0)
                val callerMethod = enclosingCallableFqn(call, fqn)
                val callName = call.nameAsString

                // Reflection / dynamic dispatch detection
                if (callName in setOf("invoke", "newInstance", "forName", "getMethod", "getDeclaredMethod")) {
                    val reflectionTarget = when (callName) {
                        "invoke" -> "java.lang.reflect.Method#invoke"
                        "newInstance" -> "java.lang.reflect.Constructor#newInstance"
                        "forName" -> "java.lang.Class#forName"
                        else -> "java.lang.reflect.Method#$callName"
                    }
                    emitCall(fqn, callerMethod, reflectionTarget, Confidence.POSSIBLE, line)
                }

                val scopeName = (call.scope.orElse(null) as? com.github.javaparser.ast.expr.NameExpr)?.nameAsString
                if (isTestClass && scopeName != null && scopeName in mockedTypes) {
                    continue
                }

                try {
                    val declaring = call.resolve().declaringType()
                    val declaringFqn = declaring.qualifiedName
                    if (isTestClass && (declaringFqn in mockedTypes || declaring.name in mockedTypes)) {
                        continue
                    }
                    val isReflection = declaringFqn.startsWith("java.lang.reflect") ||
                        (declaringFqn == "java.lang.Class" && callName in setOf("forName", "getMethod", "getDeclaredMethod", "newInstance"))
                    val conf = if (isReflection) Confidence.POSSIBLE else Confidence.CONFIRMED
                    emitCall(fqn, callerMethod, "$declaringFqn#$callName", conf, line)

                    if (declaring.isInterface && declaringFqn in projectTypes) {
                        val impls = implsByInterface[declaringFqn].orEmpty()
                        val qualifier = scopeName?.let { qualifiers[it] }
                        val matchedImpl = qualifier?.let { q ->
                            impls.firstOrNull { impl ->
                                val simple = impl.substringAfterLast('.')
                                simple.equals(q, ignoreCase = true) || simple.replaceFirstChar { it.lowercase() } == q
                            }
                        }

                        if (matchedImpl != null) {
                            emitCall(fqn, callerMethod, "$matchedImpl#$callName", Confidence.CONFIRMED, line)
                        } else if (impls.size == 1) {
                            emitCall(fqn, callerMethod, "${impls.single()}#$callName", Confidence.POSSIBLE, line)
                        }
                    }
                    continue
                } catch (e: Exception) {
                    if (e is UnsolvedSymbolException) {
                        if (callName.isNotEmpty()) {
                            unresolved += UnresolvedSymbol(
                                symbol = callName,
                                filePath = file.toString(),
                                line = line,
                                reason = e.message ?: "Unsolved method call",
                            )
                        }
                    }
                }

                if (scopeName == null) continue
                val pkg = types.find { it.fqn == fqn }?.packageName
                val fieldTypeFqn = fieldToFqn(fieldsByTypeAndName[fqn]?.get(scopeName), facade, pkg, types) ?: continue
                if (fieldTypeFqn !in projectTypes) continue
                val impls = implsByInterface[fieldTypeFqn].orEmpty()
                val qualifier = qualifiers[scopeName]
                val matchedImpl = qualifier?.let { q ->
                    impls.firstOrNull { impl ->
                        val simple = impl.substringAfterLast('.')
                        simple.equals(q, ignoreCase = true) || simple.replaceFirstChar { it.lowercase() } == q
                    }
                }

                if (matchedImpl != null) {
                    emitCall(fqn, callerMethod, "$matchedImpl#$callName", Confidence.CONFIRMED, line)
                } else when (impls.size) {
                    1 -> emitCall(fqn, callerMethod, "${impls.single()}#$callName", Confidence.POSSIBLE, line)
                    else -> {
                        if (impls.isEmpty()) {
                            emitCall(fqn, callerMethod, "$fieldTypeFqn#$callName", Confidence.POSSIBLE, line)
                        }
                    }
                }
            }

            for (ctor in typeDecl.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr::class.java)) {
                val line = ctor.range.map { it.begin.line }.orElse(0)
                val callerMethod = enclosingCallableFqn(ctor, fqn)
                try {
                    val created = facade.solve(ctor).getDeclaration()
                        .map { it.declaringType().qualifiedName }
                        .orElse(null)
                    if (created != null && created in projectTypes) {
                        emitCall(fqn, callerMethod, "$created#<init>", Confidence.CONFIRMED, line)
                    }
                } catch (_: Exception) {
                }
            }

            // Test mapping: walk from test methods outward, excluding mocked types
            if (isTestClass) {
                for (method in typeDecl.methods) {
                    val isTestMethod = method.annotations.any { ann ->
                        ann.nameAsString.substringAfterLast('.') in setOf("Test", "ParameterizedTest", "RepeatedTest", "TestFactory")
                    }
                    if (!isTestMethod) continue
                    val testMethodName = method.nameAsString
                    val coveredTargets = mutableSetOf<String>()

                    for (call in method.findAll(com.github.javaparser.ast.expr.MethodCallExpr::class.java)) {
                        val scopeName = (call.scope.orElse(null) as? com.github.javaparser.ast.expr.NameExpr)?.nameAsString
                        if (scopeName != null && scopeName in mockedTypes) continue

                        try {
                            val declaring = call.resolve().declaringType()
                            val declaringFqn = declaring.qualifiedName
                            if (declaringFqn in mockedTypes || declaring.name in mockedTypes) continue
                            if (declaringFqn in projectTypes) {
                                coveredTargets += declaringFqn
                            }
                        } catch (_: Exception) {
                            val pkg = types.find { it.fqn == fqn }?.packageName
                            val fieldTypeFqn = fieldToFqn(fieldsByTypeAndName[fqn]?.get(scopeName), facade, pkg, types)
                            if (fieldTypeFqn != null && fieldTypeFqn !in mockedTypes && fieldTypeFqn in projectTypes) {
                                coveredTargets += fieldTypeFqn
                            }
                        }
                    }

                    for (ctor in method.findAll(com.github.javaparser.ast.expr.ObjectCreationExpr::class.java)) {
                        try {
                            val created = facade.solve(ctor).getDeclaration()
                                .map { it.declaringType().qualifiedName }
                                .orElse(null)
                            if (created != null && created in projectTypes && created !in mockedTypes) {
                                coveredTargets += created
                            }
                        } catch (_: Exception) {
                        }
                    }

                    for (target in coveredTargets) {
                        testCoverageEdges += DependencyEdge(
                            sourceFqn = fqn,
                            targetFqn = target.substringBefore('#'),
                            kind = EdgeKind.TESTS,
                            confidence = Confidence.CONFIRMED,
                            callerMember = testMethodName,
                        )
                    }
                }
            }

            // MapStruct / DTO semantic mapper awareness
            val isMapper = typeDecl.annotations.any { it.nameAsString.substringAfterLast('.') == "Mapper" }
            if (isMapper) {
                for (method in typeDecl.methods) {
                    val line = method.range.map { it.begin.line }.orElse(0)
                    val callerMember = method.nameAsString
                    for (param in method.parameters) {
                        val paramSimple = param.typeAsString.substringBefore('<').substringAfterLast('.')
                        val targetType = projectTypes.firstOrNull { it.substringAfterLast('.') == paramSimple }
                        if (targetType != null && targetType != fqn) {
                            calls += DependencyEdge(fqn, targetType, EdgeKind.USES, Confidence.CONFIRMED, line, callerMember)
                        }
                    }
                    val returnSimple = method.typeAsString.substringBefore('<').substringAfterLast('.')
                    val targetType = projectTypes.firstOrNull { it.substringAfterLast('.') == returnSimple }
                    if (targetType != null && targetType != fqn) {
                        calls += DependencyEdge(fqn, targetType, EdgeKind.USES, Confidence.CONFIRMED, line, callerMember)
                    }
                }
            }
        }

        val allEdges = calls.distinctBy { e -> listOf(e.sourceFqn, e.targetFqn, e.kind, e.confidence) } +
            testCoverageEdges.distinctBy { listOf(it.sourceFqn, it.targetFqn, it.kind) }

        return allEdges.distinctBy { listOf(it.sourceFqn, it.targetFqn, it.kind, it.confidence) }
    }

    private fun enclosingCallableFqn(node: com.github.javaparser.ast.Node, typeFqn: String): String {
        var curr: com.github.javaparser.ast.Node? = node.parentNode.orElse(null)
        while (curr != null) {
            if (curr is com.github.javaparser.ast.body.MethodDeclaration) {
                return "$typeFqn#${curr.nameAsString}"
            }
            if (curr is com.github.javaparser.ast.body.ConstructorDeclaration) {
                return "$typeFqn#<init>"
            }
            curr = curr.parentNode.orElse(null)
        }
        return typeFqn
    }

    private fun extractFieldQualifiers(typeDecl: TypeDeclaration<*>): Map<String, String> {
        val qualifiers = mutableMapOf<String, String>()
        for (field in typeDecl.fields) {
            val qualifier = field.annotations.firstOrNull {
                it.nameAsString.substringAfterLast('.') in setOf("Qualifier", "Named")
            }?.let { ann -> extractAnnotationValue(ann) }
            if (qualifier != null) {
                for (v in field.variables) {
                    qualifiers[v.nameAsString] = qualifier
                }
            }
        }
        for (ctor in typeDecl.constructors) {
            for (param in ctor.parameters) {
                val qualifier = param.annotations.firstOrNull {
                    it.nameAsString.substringAfterLast('.') in setOf("Qualifier", "Named")
                }?.let { ann -> extractAnnotationValue(ann) }
                if (qualifier != null) {
                    qualifiers[param.nameAsString] = qualifier
                }
            }
        }
        return qualifiers
    }

    private fun extractAnnotationValue(ann: com.github.javaparser.ast.expr.AnnotationExpr): String? =
        when (ann) {
            is com.github.javaparser.ast.expr.SingleMemberAnnotationExpr -> stripQuotes(ann.memberValue.toString())
            is com.github.javaparser.ast.expr.NormalAnnotationExpr ->
                ann.pairs.firstOrNull { it.nameAsString == "value" }?.value?.let { stripQuotes(it.toString()) }
            else -> null
        }

    private fun extractMockedTypes(typeDecl: TypeDeclaration<*>, types: List<ParsedType>): Set<String> {
        val mocked = mutableSetOf<String>()
        for (field in typeDecl.fields) {
            val isMock = field.annotations.any {
                it.nameAsString.substringAfterLast('.') in setOf("Mock", "MockBean", "SpyBean")
            }
            if (isMock) {
                for (v in field.variables) {
                    val simpleName = v.type.asString().substringBefore('<').substringAfterLast('.')
                    mocked += v.nameAsString
                    mocked += simpleName
                    types.find { it.fqn.endsWith(".$simpleName") || it.fqn == simpleName }?.let { t ->
                        mocked += t.fqn
                        types.filter { t.fqn in it.interfaceFqns || it.superTypeFqn == t.fqn }.forEach { impl ->
                            mocked += impl.fqn
                            mocked += impl.fqn.substringAfterLast('.')
                        }
                    }
                }
            }
        }
        return mocked
    }

    private fun fieldToFqn(
        simpleTypeName: String?,
        facade: JavaParserFacade,
        contextPackage: String? = null,
        types: List<ParsedType> = emptyList(),
    ): String? {
        if (simpleTypeName == null) return null
        if (contextPackage != null) {
            val samePkg = "$contextPackage.$simpleTypeName"
            if (types.any { it.fqn == samePkg }) return samePkg
        }
        val match = types.firstOrNull { it.fqn.substringAfterLast('.') == simpleTypeName }
        if (match != null) return match.fqn

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
            val mockedTypeNames = test.fields
                .filter { it.annotations.any { a -> a in setOf("Mock", "MockBean", "SpyBean") } }
                .map { it.declaredType.substringBefore('<').substringAfterLast('.') }
                .toSet()

            val confirmed = importsByType[test.fqn].orEmpty()
                .map { it.first }
                .filter { it in productionFqns && it.substringAfterLast('.') !in mockedTypeNames }

            for (target in confirmed) {
                testEdges += DependencyEdge(test.fqn, target, EdgeKind.TESTS, Confidence.CONFIRMED)
            }

            val referencedSimpleNames = (
                test.fields.filter { !it.synthetic && it.annotations.none { a -> a in setOf("Mock", "MockBean", "SpyBean") } }
                    .map { it.declaredType.substringBefore('<').substringAfterLast('.') } +
                    test.methods.flatMap { m -> m.signature.substringAfter('(').substringBefore(')').split(',') }
                        .map { it.trim().substringBefore('<').substringAfterLast('.') } +
                    test.superTypeFqn?.let { listOf(it.substringAfterLast('.')) }.orEmpty()
                ).filter { it.isNotBlank() && it !in mockedTypeNames && it !in setOf("String", "Integer", "Long", "Boolean", "void", "List", "Map", "Set") }

            for (simple in referencedSimpleNames.toSet()) {
                val candidates = prodBySimpleName[simple].orEmpty()
                val candidate = candidates.singleOrNull()?.fqn ?: continue
                if (candidate == test.fqn || candidate in confirmed || candidate.substringAfterLast('.') in mockedTypeNames) continue
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
                    annotations = m.annotations.map { it.nameAsString.substringAfterLast('.') },
                    returnType = try { m.type.asString() } catch (_: Exception) { null },
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
        val line = type.range.map { it.begin.line }.orElse(0)
        unresolved += UnresolvedSymbol(
            symbol = type.asString(),
            filePath = file.toString(),
            line = line,
            reason = e.message ?: "Unsolved symbol: ${type.asString()}",
        )
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (e: Exception) {
        val line = type.range.map { it.begin.line }.orElse(0)
        unresolved += UnresolvedSymbol(
            symbol = type.asString(),
            filePath = file.toString(),
            line = line,
            reason = "${e.javaClass.simpleName}: ${e.message}",
        )
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
