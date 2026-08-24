package dev.repomind.core.scanner

import dev.repomind.core.model.BuildSystem
import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.ScanResult
import dev.repomind.core.model.SourceRoot
import java.io.File
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class RepositoryScanner {

    fun scan(root: Path): ScanResult {
        val rootAbs = root.toAbsolutePath().normalize()
        require(rootAbs.toFile().isDirectory) { "Repository root does not exist or is not a directory: $root" }
        val matcher = IgnoreMatcher.create(rootAbs)
        val buildSystem = detectBuildSystem(rootAbs)
        val modules = when (buildSystem) {
            BuildSystem.MAVEN -> mavenModules(rootAbs, matcher)
            BuildSystem.GRADLE_GROOVY, BuildSystem.GRADLE_KOTLIN -> gradleModules(rootAbs, matcher, buildSystem)
            BuildSystem.UNKNOWN -> listOf(moduleFor(rootAbs, null, matcher))
        }
        val fileCount = modules.sumOf { m ->
            m.sourceRoots.sumOf { sr -> countSourceFiles(sr.path, rootAbs, matcher) }
        }
        return ScanResult(
            root = rootAbs,
            buildSystem = buildSystem,
            modules = modules,
            sourceFileCount = fileCount,
        )
    }

    private fun detectBuildSystem(root: Path): BuildSystem = when {
        root.resolve("pom.xml").toFile().isFile -> BuildSystem.MAVEN
        root.resolve("build.gradle.kts").toFile().isFile -> BuildSystem.GRADLE_KOTLIN
        root.resolve("build.gradle").toFile().isFile -> BuildSystem.GRADLE_GROOVY
        else -> BuildSystem.UNKNOWN
    }

    private fun mavenModules(root: Path, matcher: IgnoreMatcher): List<RepoModule> {
        val modules = mutableListOf<RepoModule>()
        collectMavenModules(root, root, matcher, modules)
        return modules
    }

    private fun collectMavenModules(dir: Path, repoRoot: Path, matcher: IgnoreMatcher, out: MutableList<RepoModule>) {
        val pom = dir.resolve("pom.xml")
        if (!pom.toFile().isFile || out.size > 200) return
        out += moduleFor(dir, pom, matcher)
        for (child in declaredMavenSubmodules(pom)) {
            val sub = dir.resolve(child).normalize()
            if (sub.toFile().isDirectory && !matcher.isIgnored(sub.relativizeFromRoot(repoRoot), true)) {
                collectMavenModules(sub, repoRoot, matcher, out)
            }
        }
    }

    private fun declaredMavenSubmodules(pom: Path): List<String> = try {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
        val list = doc.getElementsByTagName("module")
        (0 until list.length).map { list.item(it).textContent.trim() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun gradleModules(root: Path, matcher: IgnoreMatcher, bs: BuildSystem): List<RepoModule> {
        val settingsNames = setOf("settings.gradle", "settings.gradle.kts")
        val settings = root.toFile().listFiles { f -> f.name in settingsNames }
            ?.firstOrNull { it.isFile }?.toPath()
        val included = settings?.let { parseGradleIncludes(it) } ?: emptyList()
        val modules = mutableListOf(moduleFor(root, gradleBuildFile(root, bs), matcher))
        for (inc in included) {
            val sub = root.resolve(inc.replace(':', '/')).normalize()
            if (sub.toFile().isDirectory && !matcher.isIgnored(sub.relativizeFromRoot(root), true)) {
                modules += moduleFor(sub, gradleBuildFile(sub, bs), matcher)
            }
        }
        return modules
    }

    private fun gradleBuildFile(dir: Path, bs: BuildSystem): Path? {
        val name = if (bs == BuildSystem.GRADLE_KOTLIN) "build.gradle.kts" else "build.gradle"
        return dir.resolve(name).takeIf { it.toFile().isFile }
    }

    private fun parseGradleIncludes(settings: Path): List<String> = settings.toFile().readLines()
        .map { it.trim() }
        .filter { it.startsWith("include") }
        .flatMap { line ->
            Regex("\"([^\"]+)\"|'([^']+)'").findAll(line)
                .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
                .toList()
        }

    private fun moduleFor(dir: Path, buildFile: Path?, matcher: IgnoreMatcher): RepoModule {
        val sourceRoots = SOURCE_ROOTS.mapNotNull { (rel, isTest) ->
            val src = dir.resolve(rel)
            if (src.toFile().isDirectory) SourceRoot(src, isTest) else null
        }
        return RepoModule(
            name = dir.fileName?.toString() ?: dir.toString(),
            path = dir,
            buildFile = buildFile,
            sourceRoots = sourceRoots,
        )
    }

    private fun countSourceFiles(sourceRoot: Path, repoRoot: Path, matcher: IgnoreMatcher): Int =
        sourceRoot.toFile().walkTopDown()
            .filter { it.isFile && it.extension in JAVA_EXTENSIONS }
            .count { !matcher.isIgnored(it.toPath().relativizeFromRoot(repoRoot), false) }

    companion object {
        val JAVA_EXTENSIONS = setOf("java", "kt")

        private val SOURCE_ROOTS = listOf(
            "src/main/java" to false,
            "src/main/kotlin" to false,
            "src/test/java" to true,
            "src/test/kotlin" to true,
        )
    }
}

private fun Path.relativizeFromRoot(repoRoot: Path): Path = repoRoot.relativize(this)
