package dev.repomind.cli

import dev.repomind.core.classpath.ClasspathResolutionException
import dev.repomind.core.classpath.ClasspathResolver
import dev.repomind.core.classpath.FileBasedClasspathCache
import dev.repomind.core.model.BuildSystem
import dev.repomind.core.scanner.RepositoryScanner
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
    subcommands = [ScanCommand::class, ClasspathCommand::class],
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

fun main(args: Array<String>) {
    exitProcess(CommandLine(RepomindCli()).execute(*args))
}
