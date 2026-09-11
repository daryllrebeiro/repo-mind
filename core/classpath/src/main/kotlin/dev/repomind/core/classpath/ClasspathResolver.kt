package dev.repomind.core.classpath

import dev.repomind.core.model.BuildSystem
import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.sha256Of
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class ClasspathResolver(
    private val cache: ClasspathCache,
    private val commandRunner: CommandRunner = ProcessCommandRunner,
    private val mavenBinary: String = "mvn",
    private val gradleBinary: String = "gradle",
    private val allowNetwork: Boolean = false,
) {
    private val logger = LoggerFactory.getLogger(ClasspathResolver::class.java)

    fun resolve(repoRoot: Path, module: RepoModule, buildSystem: BuildSystem): ResolvedClasspath {
        val buildFile = requireNotNull(module.buildFile) {
            "Module ${module.name} has no build file; cannot resolve classpath"
        }
        val key = sha256Of(module.path.toAbsolutePath().normalize().toString() + ":network=$allowNetwork")
        val buildFileHash = sha256Of(Files.readAllBytes(buildFile))

        logger.debug("Resolving classpath for module: ${module.name} with build system: $buildSystem")

        cache.load(key)?.let { cached ->
            if (cached.buildFileHash == buildFileHash) {
                logger.info("Using cached classpath for module: ${module.name}")
                return ResolvedClasspath(
                    module = module.path,
                    entries = cached.entries.map(Path::of),
                    fromCache = true,
                )
            }
        }

        logger.info("Resolving fresh classpath for module: ${module.name}")
        val entries = when (buildSystem) {
            BuildSystem.MAVEN -> resolveMaven(module, buildFile)
            BuildSystem.GRADLE_GROOVY, BuildSystem.GRADLE_KOTLIN -> resolveGradle(repoRoot, module)
            BuildSystem.UNKNOWN -> throw ClasspathResolutionException(
                moduleName = module.name,
                message = "Unknown build system for module ${module.name}; cannot resolve classpath",
            )
        }

        logger.info("Resolved ${entries.size} classpath entries for module: ${module.name}")
        cache.store(key, CachedClasspath(buildFileHash = buildFileHash, entries = entries))
        return ResolvedClasspath(
            module = module.path,
            entries = entries.map(Path::of),
            fromCache = false,
        )
    }

    private fun resolveMaven(module: RepoModule, pomFile: Path): List<String> {
        val outputFile = Files.createTempFile("repomind-cp-", ".txt")
        try {
            logger.debug("Running Maven classpath resolution for module: ${module.name}")
            val result = commandRunner.run(
                workDir = module.path,
                command = listOf(
                    mavenBinary,
                    "-q",
                    "-f", pomFile.toAbsolutePath().toString(),
                    "dependency:build-classpath",
                    "-Dmdep.outputFile=${outputFile.toAbsolutePath()}",
                ) + if (allowNetwork) emptyList() else listOf("--offline"),
            )
            if (result.exitCode != 0) {
                logger.error("Maven classpath resolution failed for ${module.name}: ${result.stderr}")
                throw ClasspathResolutionException(
                    moduleName = module.name,
                    message = "Maven classpath resolution failed for ${module.name} (exit ${result.exitCode})",
                    stderr = result.stderr.ifBlank { result.stdout },
                )
            }
            if (!Files.isRegularFile(outputFile)) {
                logger.error("Maven did not produce classpath output file for ${module.name}")
                throw ClasspathResolutionException(
                    moduleName = module.name,
                    message = "Maven did not produce a classpath output file for ${module.name}",
                    stderr = result.stdout,
                )
            }
            val classpath = parseClasspath(Files.readString(outputFile), module.name)
            logger.debug("Maven resolved ${classpath.size} entries for ${module.name}")
            return classpath
        } finally {
            Files.deleteIfExists(outputFile)
        }
    }

    private fun resolveGradle(repoRoot: Path, module: RepoModule): List<String> {
        val initScript = Files.createTempFile("repomind-init-", ".gradle")
        try {
            logger.debug("Running Gradle classpath resolution for module: ${module.name}")
            Files.writeString(
                initScript,
                """
                allprojects {
                    tasks.register("repomindPrintClasspath") {
                        doLast {
                            println "__CP_BEGIN__"
                            def entries = [] as Set
                            ["runtimeClasspath", "compileClasspath"].each { name ->
                                def cfg = configurations.findByName(name)
                                if (cfg != null) {
                                    cfg.resolve().each { entries << it.absolutePath }
                                }
                            }
                            entries.each { println it }
                            println "__CP_END__"
                        }
                    }
                }
                """.trimIndent(),
            )
            val result = commandRunner.run(
                workDir = repoRoot,
                command = listOf(
                    gradleBinary,
                    "-q",
                    "-I", initScript.toAbsolutePath().toString(),
                    "-p", repoRoot.toAbsolutePath().toString(),
                    ":${module.name}:repomindPrintClasspath",
                ) + if (allowNetwork) emptyList() else listOf("--offline"),
            )
            if (result.exitCode != 0) {
                logger.error("Gradle classpath resolution failed for ${module.name}: ${result.stderr}")
                throw ClasspathResolutionException(
                    moduleName = module.name,
                    message = "Gradle classpath resolution failed for ${module.name} (exit ${result.exitCode})",
                    stderr = result.stderr.ifBlank { result.stdout },
                )
            }
            val between = extractBetween(result.stdout, "__CP_BEGIN__", "__CP_END__")
            if (between.isEmpty()) {
                logger.error("Gradle produced no classpath markers for ${module.name}")
                throw ClasspathResolutionException(
                    moduleName = module.name,
                    message = "Gradle produced no classpath markers for ${module.name}",
                    stderr = result.stdout,
                )
            }
            val classpath = between.map { it.trim() }.filter { it.isNotEmpty() }
            logger.debug("Gradle resolved ${classpath.size} entries for ${module.name}")
            return classpath
        } finally {
            Files.deleteIfExists(initScript)
        }
    }

    private fun parseClasspath(raw: String, moduleName: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw ClasspathResolutionException(
                moduleName = moduleName,
                message = "Empty classpath resolved for $moduleName",
            )
        }
        return trimmed.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    private fun extractBetween(stdout: String, begin: String, end: String): List<String> {
        val lines = stdout.lines()
        val startIdx = lines.indexOfFirst { it.contains(begin) }
        if (startIdx < 0) return emptyList()
        val endIdx = lines.subList(startIdx + 1, lines.size).indexOfFirst { it.contains(end) }
        if (endIdx < 0) return emptyList()
        return lines.subList(startIdx + 1, startIdx + 1 + endIdx)
    }
}
