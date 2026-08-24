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
        config.setSymbolResolver(JavaSymbolSolver(buildTypeSolver(module.sourceRoots, classpathJars)))
        StaticJavaParser.setConfiguration(config)
        JavaParserFacade.clearInstances()

        val types = mutableListOf<ParsedType>()
        val unresolved = mutableListOf<UnresolvedSymbol>()

        for (sourceRoot in module.sourceRoots) {
            for (file in listJavaFiles(sourceRoot.path)) {
                try {
                    val cu = StaticJavaParser.parse(file)
                    val pkg = cu.packageDeclaration.map { it.nameAsString }.orElse("")
                    for (typeDecl in cu.types) {
                        extractType(typeDecl, pkg, file, unresolved)?.let { types += it }
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

        return ModuleParse(
            moduleName = module.name,
            types = types,
            unresolvedSymbols = unresolved.distinctBy { Triple(it.symbol, it.filePath, it.line) },
        )
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
                    ParsedField(
                        name = v.nameAsString,
                        declaredType = v.type.asString(),
                        visibility = visibilityOf(f),
                        isStatic = f.isStatic,
                        annotations = f.annotations.map { it.nameAsString.substringAfterLast('.') },
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
            superTypeFqn = superFqn,
            interfaceFqns = interfaceNames,
            methods = methods,
            fields = fields,
        )
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


