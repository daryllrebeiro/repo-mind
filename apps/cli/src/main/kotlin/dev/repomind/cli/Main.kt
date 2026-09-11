package dev.repomind.cli

import dev.repomind.core.classpath.ClasspathResolutionException
import dev.repomind.core.classpath.ClasspathResolver
import dev.repomind.core.classpath.FileBasedClasspathCache
import dev.repomind.core.config.ConfigurationExtractor
import dev.repomind.core.eval.CaseLoader
import dev.repomind.core.eval.EvalHarness
import dev.repomind.core.eval.EvalReport
import dev.repomind.core.graph.InMemoryGraph
import dev.repomind.core.impact.ImpactAnalyzer
import dev.repomind.core.impact.ImpactReport
import dev.repomind.core.impact.SymbolMeta
import dev.repomind.core.index.IncrementalIndexer
import dev.repomind.core.index.IncrementalResult
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.core.query.RepoQueryEngine
import dev.repomind.core.report.ReportGenerator
import dev.repomind.core.rules.RuleEvaluator
import dev.repomind.core.rules.RuleLoader
import dev.repomind.core.rules.RulesReport
import dev.repomind.core.rules.TypeStereotypeInfo
import dev.repomind.core.model.RepoMindLimits
import dev.repomind.core.model.BuildSystem
import dev.repomind.core.scanner.RepositoryScanner
import dev.repomind.language.java.JavaSemanticParser
import dev.repomind.storage.sqlite.SymbolDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
    subcommands = [
        ScanCommand::class,
        ClasspathCommand::class,
        ParseCommand::class,
        ConfigCommand::class,
        IndexCommand::class,
        EvalCommand::class,
        CallersCommand::class,
        ImpactCommand::class,
        UpdateCommand::class,
        RulesCommand::class,
        ReportCommand::class,
        WatchCommand::class,
        InitCommand::class,
        LspCommand::class,
        DeprecationsCommand::class,
        RefactorCommand::class,
    ],
)
class RepomindCli : Runnable {
    private val logger = LoggerFactory.getLogger(RepomindCli::class.java)

    override fun run() {
        logger.info("No subcommand specified. Run with --help for a list of available commands.")
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

    @picocli.CommandLine.Option(
        names = ["--online"],
        description = ["Allow the build tool to download dependencies (default: offline mode; no network access)."],
    )
    var online: Boolean = false

    override fun run() {
        val scanRoot = dev.repomind.core.model.PathGuard.requireDirectory(root)
        val scan = RepositoryScanner().scan(scanRoot)
        val resolver = ClasspathResolver(
            cache = FileBasedClasspathCache(scan.root.resolve(".repomind/cache/classpath")),
            allowNetwork = online,
        )
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
        val scan = RepositoryScanner().scan(dev.repomind.core.model.PathGuard.requireDirectory(root))
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
                        unresolvedSymbols = parsed.unresolvedSymbols.take(RepoMindLimits.MAX_UNRESOLVED_LOG).map { "${it.symbol} (${it.filePath}:${it.line})" },
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
        val scan = RepositoryScanner().scan(dev.repomind.core.model.PathGuard.requireDirectory(root))
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
                        properties = graph.properties.take(RepoMindLimits.MAX_CONFIG_PROPERTIES_DISPLAY).map { "${it.key}=${it.value} (${it.sourceFile})" },
                        bindings = graph.bindings.map {
                            BindingDto(
                                propertyKey = it.propertyKey,
                                targetFqn = it.targetFqn,
                                kind = it.kind.name,
                                memberName = it.memberName,
                                returnType = it.returnType,
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
    val returnType: String? = null,
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
        val scan = RepositoryScanner().scan(dev.repomind.core.model.PathGuard.requireDirectory(root))
        val resolver = ClasspathResolver(cache = FileBasedClasspathCache(scan.root.resolve(".repomind/cache/classpath")))
        val parser = JavaSemanticParser()
        SymbolDatabase.open(scan.root.resolve(".repomind/index.db")).use { db ->
            var symbolCount = 0
            var edgeCount = 0
            for (module in scan.modules) {
                db.recordModule(module.name, module.path.toString(), scan.buildSystem.name)
                val jars = try {
                    resolver.resolve(scan.root, module, scan.buildSystem).entries
                } catch (_: Exception) {
                    emptyList()
                }
                val parsed = parser.parseModule(module, jars)
                symbolCount += db.replaceModule(module.name, parsed)
                edgeCount += db.edges.replaceModule(module.name, parsed.edges)

                val fileStates = mutableMapOf<String, String>()
                for (sourceRoot in module.sourceRoots) {
                    sourceRoot.path.toFile().walkTopDown()
                        .filter { it.isFile && it.extension in RepositoryScanner.JAVA_EXTENSIONS }
                        .forEach { file ->
                            val rel = scan.root.relativize(file.toPath()).toString().replace('\\', '/')
                            fileStates[rel] = dev.repomind.core.model.sha256Of(java.nio.file.Files.readAllBytes(file.toPath()))
                        }
                }
                db.setFileStates(module.name, fileStates)
            }
            val report = db.confidenceReport()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            println(
                Json.encodeToString(
                    IndexResultDto.serializer(),
                    IndexResultDto(
                        modules = scan.modules.size,
                        symbolsIndexed = symbolCount,
                        edgesIndexed = edgeCount,
                        totalSymbols = report.totalSymbols,
                        totalEdges = report.totalEdges,
                        totalUnresolved = report.totalUnresolved,
                        confidenceRate = report.confidenceRate,
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
    val totalUnresolved: Long,
    val confidenceRate: Double,
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
        val scan = RepositoryScanner().scan(dev.repomind.core.model.PathGuard.requireDirectory(root))
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
        val startedAt = System.nanoTime()
        val dbPath = root.toAbsolutePath().normalize().resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath — run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }
        val repomindDir = root.toAbsolutePath().normalize().resolve(".repomind")
        val client = dev.repomind.core.index.DaemonClient(repomindDir)
        if (client.isAvailable()) {
            val callers = client.queryCallers(symbol)
            if (callers != null) {
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                println(
                    Json.encodeToString(
                        CallersResultDto.serializer(),
                        CallersResultDto(symbol = symbol, callerCount = callers.size, callers = callers, elapsedMs = elapsedMs),
                    ),
                )
                return
            }
        }

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

@Command(
    name = "impact",
    description = ["Deterministic impact analysis with evidence-traceable scoring (run 'repomind index' first)."],
)
class ImpactCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @Parameters(index = "1", description = ["Fully-qualified symbol name"])
    lateinit var symbol: String

    override fun run() {
        val dbPath = root.toAbsolutePath().normalize().resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath â€” run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }
        dev.repomind.core.query.RepoQueryEngine(dbPath).use { engine ->
            val report = engine.impact(symbol)
            println(Json.encodeToString(ImpactReport.serializer(), report))
        }
    }
}

@Command(
    name = "update",
    description = ["Incrementally re-index only changed files (falls back to full index on first run)."],
)
class UpdateCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    override fun run() {
        val result = IncrementalIndexer(root.toAbsolutePath().normalize().resolve(".repomind/index.db")).update(root)
        println(Json.encodeToString(IncrementalResult.serializer(), result))
    }
}

@Command(
    name = "rules",
    description = ["Evaluate architecture rules (.repomind/rules.yaml or explicit file) against the indexed graph."],
)
class RulesCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @Parameters(index = "1", description = ["Optional rules YAML path"], arity = "0..1")
    var rulesFile: Path? = null

    @picocli.CommandLine.Option(
        names = ["--check-cycles"],
        description = ["Detect circular dependencies between packages"],
    )
    var checkCycles: Boolean = false

    @picocli.CommandLine.Option(
        names = ["--preset"],
        description = ["Apply built-in architecture preset: hexagonal, clean, three-tier"],
    )
    var presetName: String? = null

    @picocli.CommandLine.Option(
        names = ["--suggest-adr"],
        description = ["Mine codebase dependency conventions and draft Architecture Decision Records (ADRs)"],
    )
    var suggestAdr: Boolean = false

    @picocli.CommandLine.Option(
        names = ["--adr-dir"],
        description = ["Target directory to save drafted ADR markdown files (e.g. docs/adr)"],
    )
    var adrDir: Path? = null

    @picocli.CommandLine.Option(
        names = ["--fail-on-violation"],
        description = ["Exit with status code 1 if architecture violations are found"],
    )
    var failOnViolation: Boolean = false

    override fun run() {
        val dbPath = root.toAbsolutePath().normalize().resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath — run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }

        SymbolDatabase.open(dbPath).use { db ->
            if (suggestAdr) {
                val types = db.allTypes().map { TypeStereotypeInfo(it.qualifiedName, it.annotations) }
                val edges = db.edges.findAll().map { row ->
                    DependencyEdge(row.sourceFqn, row.targetFqn, EdgeKind.valueOf(row.kind), Confidence.valueOf(row.confidence))
                }
                val adrs = dev.repomind.core.rules.AdrGenerator.generateAdrs(types, edges)
                if (adrDir != null) {
                    val targetDir = if (adrDir!!.isAbsolute) adrDir!! else root.resolve(adrDir!!)
                    val written = dev.repomind.core.rules.AdrGenerator.writeAdrs(adrs, targetDir)
                    System.err.println("Generated ${written.size} ADR(s) in $targetDir")
                }
                println(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(dev.repomind.core.rules.AdrDocument.serializer()), adrs))
                return
            }

            val rulesPath = rulesFile ?: dbPath.resolveSibling("rules.yaml")
            val fileRules = RuleLoader.load(rulesPath)
            val presetRules = presetName?.let { id ->
                val p = dev.repomind.core.rules.ArchitecturePreset.fromId(id)
                if (p != null) dev.repomind.core.rules.ArchitecturePreset.generateRules(p) else null
            }
            val rules = presetRules ?: fileRules

            val report = RuleEvaluator().evaluate(
                rules = rules,
                types = db.allTypes().map { TypeStereotypeInfo(it.qualifiedName, it.annotations) },
                edges = db.edges.findAll().map { row ->
                    DependencyEdge(row.sourceFqn, row.targetFqn, EdgeKind.valueOf(row.kind), Confidence.valueOf(row.confidence))
                },
                failOnViolation = false,
                checkCycles = checkCycles,
            )
            println(Json.encodeToString(RulesReport.serializer(), report))

            if (failOnViolation && !report.passed) {
                kotlin.system.exitProcess(1)
            }
        }
    }
}

@Command(
    name = "watch",
    description = ["Watch repository for file changes and incrementally update the index in the background."],
)
class WatchCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @picocli.CommandLine.Option(
        names = ["--debounce-ms"],
        description = ["Debounce duration in milliseconds (default: 300)"],
    )
    var debounceMs: Long = 300L

    @picocli.CommandLine.Option(
        names = ["--max-iterations"],
        description = ["Maximum updates before exiting (default: infinite)"],
    )
    var maxIterations: Int = -1

    @picocli.CommandLine.Option(
        names = ["--timeout-ms"],
        description = ["Maximum run duration in milliseconds before exiting (default: infinite)"],
    )
    var timeoutMs: Long = -1L

    @picocli.CommandLine.Option(
        names = ["--quiet"],
        description = ["Suppress incremental update output"],
    )
    var quiet: Boolean = false

    override fun run() {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val repomindDir = normalizedRoot.resolve(".repomind")
        val dbPath = repomindDir.resolve("index.db")
        if (!java.nio.file.Files.exists(repomindDir)) {
            java.nio.file.Files.createDirectories(repomindDir)
        }

        val pidLock = dev.repomind.core.index.DaemonPidLock(repomindDir)
        try {
            pidLock.acquire()
        } catch (e: dev.repomind.core.index.DaemonAlreadyRunningException) {
            System.err.println("ERROR: ${e.message}")
            kotlin.system.exitProcess(1)
        }

        val indexer = IncrementalIndexer(dbPath)
        System.err.println("RepoMind Watcher started on $normalizedRoot (press Ctrl+C to stop)...")

        val daemonServer = dev.repomind.core.index.DaemonServer(repomindDir, dbPath)
        daemonServer.start()

        val watcher = dev.repomind.core.index.RepositoryWatcher(
            repoRoot = normalizedRoot,
            indexer = indexer,
            debounceMs = debounceMs,
            onUpdate = { result ->
                if (!quiet) {
                    println(Json.encodeToString(IncrementalResult.serializer(), result))
                }
            },
        )

        Runtime.getRuntime().addShutdownHook(Thread {
            daemonServer.stop()
            watcher.stop()
            pidLock.release()
        })

        try {
            watcher.start(maxIterations, timeoutMs)
        } finally {
            daemonServer.stop()
            pidLock.release()
        }
    }
}

@Command(
    name = "init",
    description = ["Initialize .repomind directory and architecture rules configuration."],
)
class InitCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @picocli.CommandLine.Option(
        names = ["--preset"],
        description = ["Architecture preset: hexagonal, clean, three-tier (default: three-tier)"],
    )
    var presetName: String = "three-tier"

    @picocli.CommandLine.Option(
        names = ["--suggest-adr"],
        description = ["Mine repository architecture and generate drafted ADRs in docs/adr/"],
    )
    var suggestAdr: Boolean = false

    override fun run() {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val repomindDir = normalizedRoot.resolve(".repomind")
        java.nio.file.Files.createDirectories(repomindDir)

        val preset = dev.repomind.core.rules.ArchitecturePreset.fromId(presetName)
            ?: dev.repomind.core.rules.ArchitecturePreset.THREE_TIER

        val dbPath = repomindDir.resolve("index.db")
        val basePkg = if (java.nio.file.Files.isRegularFile(dbPath)) {
            SymbolDatabase.open(dbPath).use { db ->
                dev.repomind.core.rules.RuleGenerator.inferBasePackage(db.allTypes().map { it.qualifiedName })
            }
        } else null

        val yaml = dev.repomind.core.rules.RuleGenerator.generateYaml(preset, basePkg)
        val rulesFile = repomindDir.resolve("rules.yaml")
        if (!java.nio.file.Files.exists(rulesFile)) {
            java.nio.file.Files.writeString(rulesFile, yaml)
            println("Initialized ${rulesFile} with preset '${preset.id}'")
        } else {
            System.err.println("NOTE: ${rulesFile} already exists; leaving unchanged.")
        }

        if (suggestAdr && java.nio.file.Files.isRegularFile(dbPath)) {
            SymbolDatabase.open(dbPath).use { db ->
                val types = db.allTypes().map { TypeStereotypeInfo(it.qualifiedName, it.annotations) }
                val edges = db.edges.findAll().map { row ->
                    DependencyEdge(row.sourceFqn, row.targetFqn, EdgeKind.valueOf(row.kind), Confidence.valueOf(row.confidence))
                }
                val adrs = dev.repomind.core.rules.AdrGenerator.generateAdrs(types, edges, basePkg)
                val adrDir = normalizedRoot.resolve("docs/adr")
                val written = dev.repomind.core.rules.AdrGenerator.writeAdrs(adrs, adrDir)
                println("Generated ${written.size} ADR(s) in $adrDir")
            }
        }
    }
}

@Command(
    name = "report",
    description = ["Generate a deterministic markdown analysis report (diagrams, flows, hotspots, findings) from the index."],
)
class ReportCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @picocli.CommandLine.Option(names = ["-o", "--output"], description = ["Output markdown path (default <repo>/.repomind/report.md)"])
    var output: Path? = null

    override fun run() {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val dbPath = normalizedRoot.resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath â€” run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }
        val markdown = ReportGenerator(dbPath, repoName = normalizedRoot.fileName.toString()).generate()
        val outPath = output ?: normalizedRoot.resolve(".repomind/report.md")
        java.nio.file.Files.createDirectories(outPath.toAbsolutePath().parent)
        java.nio.file.Files.writeString(outPath, markdown)
        println(
            Json.encodeToString(
                ReportResultDto.serializer(),
                ReportResultDto(path = outPath.toString(), bytes = markdown.length),
            ),
        )
    }
}

@Serializable
data class ReportResultDto(val path: String, val bytes: Int)

@Command(
    name = "lsp",
    description = ["Start Language Server Protocol (LSP) daemon for IDE integration (VS Code, Neovim)."],
)
class LspCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"], arity = "0..1")
    var root: Path? = null

    @picocli.CommandLine.Option(names = ["--port"], description = ["Run LSP over TCP socket instead of stdio"])
    var port: Int = 0

    override fun run() {
        val repoRoot = (root ?: Path.of(".")).toAbsolutePath().normalize()
        val server = dev.repomind.cli.lsp.RepoMindLspServer(repoRoot)
        if (port > 0) {
            server.startSocket(port)
        } else {
            server.startStdio()
        }
    }
}

@Command(
    name = "deprecations",
    aliases = ["radar"],
    description = ["Scan for @Deprecated symbols, calculate blast radius debt, and suggest migration paths."],
)
class DeprecationsCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @picocli.CommandLine.Option(names = ["-o", "--output"], description = ["Output markdown path for deprecation report"])
    var output: Path? = null

    @picocli.CommandLine.Option(names = ["--json"], description = ["Output report as JSON"])
    var jsonOutput: Boolean = false

    override fun run() {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val dbPath = normalizedRoot.resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath — run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }

        SymbolDatabase.open(dbPath).use { db ->
            val deprecatedRows = db.findDeprecatedSymbols()
            val candidates = deprecatedRows.map {
                dev.repomind.core.impact.DeprecatedSymbolCandidate(
                    fqn = it.qualifiedName,
                    kind = it.kind,
                    module = it.module,
                    filePath = it.filePath,
                    line = it.lineStart,
                    annotations = it.annotations,
                )
            }

            val radar = dev.repomind.core.impact.DeprecationRadar(db.graphStore)
            val report = radar.scan(candidates)

            if (output != null) {
                val targetPath = if (output!!.isAbsolute) output!! else normalizedRoot.resolve(output!!)
                java.nio.file.Files.createDirectories(targetPath.toAbsolutePath().parent)
                java.nio.file.Files.writeString(targetPath, report.toMarkdown())
                System.err.println("Deprecation report written to $targetPath")
            }

            if (jsonOutput) {
                println(Json.encodeToString(dev.repomind.core.impact.DeprecationRadarReport.serializer(), report))
            } else if (output == null) {
                println(report.toMarkdown())
            }
        }
    }
}

@Command(
    name = "refactor",
    description = ["Automated architectural refactoring engine (dead code removal, deprecated method migration)."],
)
class RefactorCommand : Runnable {
    @Parameters(index = "0", description = ["Repository root directory"])
    lateinit var root: Path

    @picocli.CommandLine.Option(names = ["--dead-code"], description = ["Detect and remove unused private methods with 0 callers"])
    var deadCode: Boolean = false

    @picocli.CommandLine.Option(names = ["--migrate-deprecated"], description = ["Auto-rewrite calls to deprecated methods with recommended replacements"])
    var migrateDeprecated: Boolean = false

    @picocli.CommandLine.Option(names = ["--apply"], description = ["Apply changes to disk (default is dry-run diff preview)"])
    var apply: Boolean = false

    @picocli.CommandLine.Option(names = ["--create-pr"], description = ["Print GitHub PR creation command template"])
    var createPr: Boolean = false

    override fun run() {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val dbPath = normalizedRoot.resolve(".repomind/index.db")
        if (!java.nio.file.Files.isRegularFile(dbPath)) {
            System.err.println("ERROR: no index at $dbPath — run 'repomind index <repo>' first")
            kotlin.system.exitProcess(1)
        }

        SymbolDatabase.open(dbPath).use { db ->
            val fileDiffs = mutableListOf<dev.repomind.language.java.refactor.FileDiff>()
            var totalTransformations = 0

            // 1. Dead code removal
            if (deadCode) {
                val allSymbols = db.allTypes().flatMap { t -> db.findByFilePath(t.filePath ?: "") }
                val privateMethodsByFile = allSymbols.filter { it.kind == "METHOD" && it.visibility == "PRIVATE" && !it.filePath.isNullOrBlank() }
                    .groupBy { it.filePath!! }

                for ((filePathStr, methods) in privateMethodsByFile) {
                    val deadMethodNames = methods.filter { m ->
                        val callers = db.edges.findDirectCallers(m.qualifiedName)
                        callers.isEmpty()
                    }.map { it.name }.toSet()

                    if (deadMethodNames.isNotEmpty()) {
                        val filePath = Path.of(filePathStr)
                        if (java.nio.file.Files.isRegularFile(filePath)) {
                            val original = java.nio.file.Files.readString(filePath)
                            val (modified, removed) = dev.repomind.language.java.refactor.AstRefactoringEngine.removeDeadMethods(original, deadMethodNames)
                            if (removed.isNotEmpty()) {
                                totalTransformations += removed.size
                                val diff = dev.repomind.language.java.refactor.AstRefactoringEngine.generateUnifiedDiff(filePathStr, original, modified)
                                fileDiffs.add(
                                    dev.repomind.language.java.refactor.FileDiff(
                                        filePath = filePathStr,
                                        originalContent = original,
                                        modifiedContent = modified,
                                        diffUnified = diff,
                                        transformationsApplied = removed.map { "Removed dead method: $it" },
                                    )
                                )
                                if (apply) {
                                    java.nio.file.Files.writeString(filePath, modified)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Migrate deprecated methods
            if (migrateDeprecated) {
                val deprecatedSymbols = db.findDeprecatedSymbols()
                val candidates = deprecatedSymbols.map {
                    dev.repomind.core.impact.DeprecatedSymbolCandidate(
                        fqn = it.qualifiedName,
                        kind = it.kind,
                        module = it.module,
                        filePath = it.filePath,
                        line = it.lineStart,
                        annotations = it.annotations,
                    )
                }
                val radar = dev.repomind.core.impact.DeprecationRadar(db.graphStore)
                val report = radar.scan(candidates)

                for (item in report.items) {
                    val replacement = item.replacementHint ?: continue
                    val oldMethodName = item.fqn.substringAfter('#')
                    val newMethodName = replacement.substringAfter('#')
                    if (oldMethodName.isNotBlank() && newMethodName.isNotBlank() && oldMethodName != newMethodName) {
                        for (callerFqn in item.directCallers) {
                            val callerType = callerFqn.substringBefore('#')
                            val callerRows = db.findByFqn(callerType)
                            val callerFile = callerRows.firstOrNull()?.filePath
                            if (callerFile != null) {
                                val path = Path.of(callerFile)
                                if (java.nio.file.Files.isRegularFile(path)) {
                                    val original = java.nio.file.Files.readString(path)
                                    val (modified, count) = dev.repomind.language.java.refactor.AstRefactoringEngine.replaceMethodCalls(original, oldMethodName, newMethodName)
                                    if (count > 0) {
                                        totalTransformations += count
                                        val diff = dev.repomind.language.java.refactor.AstRefactoringEngine.generateUnifiedDiff(callerFile, original, modified)
                                        fileDiffs.add(
                                            dev.repomind.language.java.refactor.FileDiff(
                                                filePath = callerFile,
                                                originalContent = original,
                                                modifiedContent = modified,
                                                diffUnified = diff,
                                                transformationsApplied = listOf("Replaced $count call(s) from $oldMethodName to $newMethodName"),
                                            )
                                        )
                                        if (apply) {
                                            java.nio.file.Files.writeString(path, modified)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val result = dev.repomind.language.java.refactor.RefactoringResult(
                totalFilesChanged = fileDiffs.size,
                totalTransformations = totalTransformations,
                fileDiffs = fileDiffs,
                appliedToDisk = apply,
            )

            if (!apply) {
                for (d in fileDiffs) {
                    println(d.diffUnified)
                }
                System.err.println("Dry-run preview: ${result.totalTransformations} transformation(s) across ${result.totalFilesChanged} file(s). Run with --apply to write changes.")
            } else {
                System.err.println("Applied ${result.totalTransformations} transformation(s) across ${result.totalFilesChanged} file(s).")
            }

            if (createPr) {
                println("gh pr create --title \"refactor: automated architectural refactoring via RepoMind\" --body \"Applied ${result.totalTransformations} transformation(s) across ${result.totalFilesChanged} file(s).\"")
            }
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(RepomindCli()).execute(*args))
}

