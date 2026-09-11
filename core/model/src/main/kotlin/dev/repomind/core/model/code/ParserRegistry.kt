package dev.repomind.core.model.code

import dev.repomind.core.model.RepoModule
import java.nio.file.Path

class ParserRegistry(private val parsers: MutableList<LanguageParser> = mutableListOf()) {

    fun register(parser: LanguageParser) {
        parsers += parser
    }

    fun forExtension(extension: String): LanguageParser? =
        parsers.firstOrNull { extension in it.supportedExtensions }

    fun parse(module: RepoModule, classpath: List<Path> = emptyList()): ModuleParse {
        val matchingParsers = parsers.filter { parser ->
            module.sourceRoots.any { root ->
                root.path.toFile().walkTopDown().any { file ->
                    file.isFile && file.extension in parser.supportedExtensions
                }
            }
        }
        if (matchingParsers.isEmpty()) {
            return ModuleParse(module.name, emptyList(), emptyList(), emptyList())
        }

        val allTypes = mutableListOf<ParsedType>()
        val allUnresolved = mutableListOf<UnresolvedSymbol>()
        val allEdges = mutableListOf<DependencyEdge>()

        for (p in matchingParsers) {
            val res = p.parseModule(module, classpath)
            allTypes += res.types
            allUnresolved += res.unresolvedSymbols
            allEdges += res.edges
        }

        return ModuleParse(
            moduleName = module.name,
            types = allTypes,
            unresolvedSymbols = allUnresolved,
            edges = allEdges.distinctBy { listOf(it.sourceFqn, it.targetFqn, it.kind, it.confidence) },
        )
    }
}
