package dev.repomind.cli

import dev.repomind.core.classpath.ClasspathResolutionException
import dev.repomind.core.classpath.ClasspathResolver
import dev.repomind.core.classpath.FileBasedClasspathCache
import dev.repomind.core.config.ConfigurationExtractor
import dev.repomind.core.eval.CaseLoader
import dev.repomind.core.eval.EvalHarness
import dev.repomind.core.eval.EvalReport
import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.model.BuildSystem
import dev.repomind.core.scanner.RepositoryScanner
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Parameters
import java.nio.file.Path
import kotlin.system.exitProcess

@Command(
    name = "repomind",
    mixinStandardHelpOptions = true,
    version = ["repomind 0.1.0"],
    description = ["Codebase intelligence engine for AI agents."],
    subcommands = [ScanCommand::class, ClasspathCommand::class, ParseCommand::class, ConfigCommand::class, IndexCommand::class, EvalCommand::class, CallersCommand::class],
)
class RepomindCli : Runnable {
    override fun run() {
        println("Use a subcommand: scan, classpath")
    }
}

@Command(name = "scan", description = ["Scan a repository and report its structure."])
class ScanCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    override fun run() {
        val result = RepositoryScanner().scan(root)
        println(Json.encodeToString(ScanResultDto.serializer(), ScanResultDto.from(result)))
    }
}

@Command(name = "classpath", description = ["Resolve the dependency classpath for every module of a repository."])
class ClasspathCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    override fun run() {
        val scan = RepositoryScanner().scan(root)
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(scan.root.resolve(".repomind/cache/classpath")))
        for (module in scan.modules) {
            try {
                val cp = resolver.resolve(scan.root, module, scan.buildSystem)
                println(
                    Json.encodeToString(
                        ClasspathResultDto.serializer(),
                        ClasspathResultDto(
                            module = module.name,
                            fromCache = cp.fromCache,
                            entryCount = cp.entries.size,
                            entries = cp.entries.map { it.toString() },
                        ),
                    ),
                )
            } catch (e: ClasspathResolutionException) {
                System.err.println("ERROR: ${e.message}")
                e.stderr?.let { System.err.println(it) }
                exitProcess(1)
            }
        }
    }
}

@Serializable
data class ClasspathResultDto(
    val module: String,
    val fromCache: Boolean,
    val entryCount: Int,
    val entries: List<String>,
)

@Command(name = "parse", description = ["Semantically parse Java sources and report the extracted code model."])
class ParseCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    override fun run() {
        val scan = RepositoryScanner().scan(root)
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(scan.root.resolve(".repomind/cache/classpath")))
        val parser = JavaSemanticParser()
        for (module in scan.modules) {
            val jars = try {
                resolver.resolve(scan.root, module, scan.buildSystem).entries
            } catch (_: Exception) {
                emptyList()
            }
            val parsed = parser.parseModule(module, jars)
            println(
                Json.encodeToString(
                    ModuleParseDto.serializer(),
                    ModuleParseDto(
                        module = parsed.moduleName,
                        typeCount = parsed.typeCount,
                        methodCount = parsed.types.sumOf { it.methods.size },
                        fieldCount = parsed.fieldsCount(),
                        unresolvedCount = parsed.unresolvedCount,
                        unresolvedSymbols = parsed.unresolvedSymbols.take(20).map { "${it.symbol} (${it.filePath}:${it.line})" },
                        types = parsed.types.map { it.fqn },
                    ),
                ),
            )
        }
    }
}

private fun dev.repomind.core.model.code.ModuleParse.fieldsCount(): Int = types.sumOf { it.fields.size }

@Serializable
data class ModuleParseDto(
    val module: String,
    val typeCount: Int,
    val methodCount: Int,
    val fieldCount: Int,
    val unresolvedCount: Int,
    val unresolvedSymbols: List<String>,
    val types: List<String>,
)

@Command(name = "config", description = ["Extract the configuration graph: properties from YAML/properties files bound to code."])
class ConfigCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    override fun run() {
        val scan = RepositoryScanner().scan(root)
        val parser = JavaSemanticParser()
        val extractor = ConfigurationExtractor()
        for (module in scan.modules) {
            val parsed = parser.parseModule(module, emptyList())
            val graph = extractor.extract(module, parsed)
            println(
                Json.encodeToString(
                    ConfigGraphDto.serializer(),
                    ConfigGraphDto(
                        module = graph.moduleName,
                        propertyCount = graph.properties.size,
                        properties = graph.properties.take(50).map { "${it.key}=${it.value} (${it.sourceFile})" },
                        bindings = graph.bindings.map {
                            BindingDto(
                                propertyKey = it.propertyKey,
                                targetFqn = it.targetFqn,
                                kind = it.kind.name,
                                memberName = it.memberName,
                            )
                        },
                    ),
                ),
            )
        }
    }
}

@Serializable
data class BindingDto(
    val propertyKey: String,
    val targetFqn: String,
    val kind: String,
    val memberName: String?,
)

@Serializable
data class ConfigGraphDto(
    val module: String,
    val propertyCount: Int,
    val properties: List<String>,
    val bindings: List<BindingDto>,
)

@Command(name = "index", description = ["Parse the repository and persist a queryable symbol index to SQLite."])
class IndexCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    override fun run() {
        val startedAt = System.nanoTime()
        val scan = RepositoryScanner().scan(root)
        val parser = JavaSemanticParser()
        SymbolDatabase.open(scan.root.resolve(".repomind/index.db")).use { db ->
            var symbolCount = 0
            var edgeCount = 0
            for (module in scan.modules) {
                val parsed = parser.parseModule(module, emptyList())
                symbolCount += db.replaceModule(module.name, parsed)
                edgeCount += db.edges.replaceModule(module.name, parsed.edges)
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            println(
                Json.encodeToString(
                    IndexResultDto.serializer(),
                    IndexResultDto(
                        modules = scan.modules.size,
                        symbolsIndexed = symbolCount,
                        edgesIndexed = edgeCount,
                        totalSymbols = db.count(),
                        totalEdges = db.edges.count(),
                        elapsedMs = elapsedMs,
                    ),
                ),
            )
        }
    }
}

@Serializable
data class IndexResultDto(
    val modules: Int,
    val symbolsIndexed: Int,
    val edgesIndexed: Int,
    val totalSymbols: Long,
    val totalEdges: Long,
    val elapsedMs: Long,
)

@Command(
    name = "eval",
    description = ["Run labeled eval cases against the extracted call graph and report precision/recall."],
)
class EvalCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @Parameters(index = "1", description = ["Path to eval cases JSON file"])
    lateinit var casesFile: Path

    override fun run() {
        val scan = RepositoryScanner().scan(root)
        val parser = JavaSemanticParser()
        val harness = EvalHarness()
        val cases = CaseLoader.load(casesFile)

        for (module in scan.modules) {
            val parsed = parser.parseModule(module, emptyList())
            val report = harness.run(parsed.edges, cases)
            println(Json.encodeToString(EvalReport.serializer(), report))
        }
    }
}

@Command(
    name = "callers",
    description = ["Transitive callers of a symbol from the persisted index (run 'repomind index' first)."],
)
class CallersCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @Parameters(index = "1", description = ["Fully-qualified symbol name"])
    lateinit var symbol: String

    override fun run() {
        val dbPath = root.toAbsolutePath().normalize().resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath — run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }
        val startedAt = System.nanoTime()
        SymbolDatabase.open(dbPath).use { db ->
            val graph = InMemoryGraph(
                db.edges.findAll().map { row ->
                    dev.repomind.core.model.code.DependencyEdge(
                        sourceFqn = row.sourceFqn,
                        targetFqn = row.targetFqn,
                        kind = dev.repomind.core.model.code.EdgeKind.valueOf(row.kind),
                        confidence = dev.repomind.core.model.code.Confidence.valueOf(row.confidence),
                    )
                },
            )
            val callers = graph.transitiveCallers(symbol)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            println(
                Json.encodeToString(
                    CallersResultDto.serializer(),
                    CallersResultDto(symbol = symbol, callerCount = callers.size, callers = callers.sorted(), elapsedMs = elapsedMs),
                ),
            )
        }
    }
}

@Serializable
data class CallersResultDto(
    val symbol: String,
    val callerCount: Int,
    val callers: List<String>,
    val elapsedMs: Long,
)

fun main(args: Array<String>) {
    exitProcess(CommandLine(RepomindCli()).execute(*args))
}
