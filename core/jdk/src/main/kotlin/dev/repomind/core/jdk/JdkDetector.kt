package dev.repomind.core.jdk

import dev.repomind.core.model.BuildSystem
import dev.repomind.core.model.RepoModule
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Detects the target JDK version for a repository module from various sources.
 * Supports detection from Maven (pom.xml) and Gradle (build.gradle, build.gradle.kts) files.
 */
class JdkDetector {

    /**
     * Detects the JDK version for a given module.
     * @param module The repository module to analyze
     * @param buildSystem The detected build system
     * @return The detected JDK version, or a sensible default if detection fails
     */
    fun detectJdkVersion(module: RepoModule, buildSystem: BuildSystem): JdkVersion {
        val buildFile = module.buildFile ?: return JdkVersion.JDK_21 // sensible default

        return when (buildSystem) {
            BuildSystem.MAVEN -> detectFromMavenPom(buildFile)
            BuildSystem.GRADLE_GROOVY -> detectFromGradleGroovy(buildFile)
            BuildSystem.GRADLE_KOTLIN -> detectFromGradleKotlin(buildFile)
            BuildSystem.UNKNOWN -> detectFromJavaVersionFile(module.path)
        }
    }

    private fun detectFromMavenPom(pomFile: Path): JdkVersion {
        if (!Files.exists(pomFile)) return JdkVersion.JDK_21

        return try {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile.toFile())
            
            // Try maven.compiler.source
            val sourceProperties = doc.getElementsByTagName("maven.compiler.source")
            if (sourceProperties.length > 0) {
                val version = sourceProperties.item(0).textContent.trim()
                return JdkVersion.fromString(version)
            }

            // Try maven.compiler.target
            val targetProperties = doc.getElementsByTagName("maven.compiler.target")
            if (targetProperties.length > 0) {
                val version = targetProperties.item(0).textContent.trim()
                return JdkVersion.fromString(version)
            }

            // Try to detect from maven-compiler-plugin configuration
            val plugins = doc.getElementsByTagName("plugin")
            for (i in 0 until plugins.length) {
                val plugin = plugins.item(i)
                val artifactIds = plugin.childNodes.let { nodes ->
                    (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
                }
                
                for (element in artifactIds) {
                    if (element.tagName == "artifactId" && element.textContent.contains("maven-compiler-plugin")) {
                        // Look for source/target in configuration
                        val configuration = element.parentNode?.childNodes?.let { nodes ->
                            (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
                        }?.find { it.tagName == "configuration" }
                        
                        configuration?.let { config ->
                            val source = config.getElementsByTagName("source")
                            if (source.length > 0) {
                                return JdkVersion.fromString(source.item(0).textContent.trim())
                            }
                            val target = config.getElementsByTagName("target")
                            if (target.length > 0) {
                                return JdkVersion.fromString(target.item(0).textContent.trim())
                            }
                        }
                    }
                }
            }

            // Try to detect from java.version property
            val properties = doc.getElementsByTagName("properties")
            if (properties.length > 0) {
                val props = properties.item(0).childNodes
                for (i in 0 until props.length) {
                    val prop = props.item(i) as? org.w3c.dom.Element
                    if (prop?.tagName == "java.version") {
                        return JdkVersion.fromString(prop.textContent.trim())
                    }
                }
            }

            JdkVersion.JDK_21 // default if nothing found
        } catch (e: Exception) {
            JdkVersion.JDK_21 // default on error
        }
    }

    private fun detectFromGradleGroovy(buildFile: Path): JdkVersion {
        if (!Files.exists(buildFile)) return JdkVersion.JDK_21

        return try {
            val content = Files.readString(buildFile)
            
            // Try sourceCompatibility
            val sourceMatch = Regex("""sourceCompatibility\s*=\s*['"]?(\d+)['"]?""").find(content)
            if (sourceMatch != null) {
                return JdkVersion.fromMajor(sourceMatch.groupValues[1].toIntOrNull() ?: 21)
            }

            // Try targetCompatibility
            val targetMatch = Regex("""targetCompatibility\s*=\s*['"]?(\d+)['"]?""").find(content)
            if (targetMatch != null) {
                return JdkVersion.fromMajor(targetMatch.groupValues[1].toIntOrNull() ?: 21)
            }

            // Try toolchain configuration
            val toolchainMatch = Regex("""toolchain\s*\{[^}]*java\.version\s*=\s*['"]?(\d+)['"]?""").find(content)
            if (toolchainMatch != null) {
                return JdkVersion.fromMajor(toolchainMatch.groupValues[1].toIntOrNull() ?: 21)
            }

            JdkVersion.JDK_21 // default if nothing found
        } catch (e: Exception) {
            JdkVersion.JDK_21 // default on error
        }
    }

    private fun detectFromGradleKotlin(buildFile: Path): JdkVersion {
        if (!Files.exists(buildFile)) return JdkVersion.JDK_21

        return try {
            val content = Files.readString(buildFile)
            
            // Try sourceCompatibility
            val sourceMatch = Regex("""sourceCompatibility\s*=\s*JavaVersion\.toVersion\(\s*['"]?(\d+)['"]?\s*\)""").find(content)
                ?: Regex("""sourceCompatibility\s*=\s*['"]?(\d+)['"]?""").find(content)
            if (sourceMatch != null) {
                return JdkVersion.fromMajor(sourceMatch.groupValues[1].toIntOrNull() ?: 21)
            }

            // Try targetCompatibility
            val targetMatch = Regex("""targetCompatibility\s*=\s*JavaVersion\.toVersion\(\s*['"]?(\d+)['"]?\s*\)""").find(content)
                ?: Regex("""targetCompatibility\s*=\s*['"]?(\d+)['"]?""").find(content)
            if (targetMatch != null) {
                return JdkVersion.fromMajor(targetMatch.groupValues[1].toIntOrNull() ?: 21)
            }

            // Try toolchain configuration
            val toolchainMatch = Regex("""toolchain\s*\{[^}]*languageVersion\.set\(\s*JavaLanguageVersion\.of\(\s*(\d+)\s*\)\s*\)""").find(content)
                ?: Regex("""toolchain\s*\{[^}]*java\.version\s*=\s*['"]?(\d+)['"]?""").find(content)
            if (toolchainMatch != null) {
                return JdkVersion.fromMajor(toolchainMatch.groupValues[1].toIntOrNull() ?: 21)
            }

            JdkVersion.JDK_21 // default if nothing found
        } catch (e: Exception) {
            JdkVersion.JDK_21 // default on error
        }
    }

    private fun detectFromJavaVersionFile(modulePath: Path): JdkVersion {
        // Try .java-version file (used by SDKMAN and other tools)
        val javaVersionFile = modulePath.resolve(".java-version")
        if (Files.exists(javaVersionFile)) {
            return try {
                val version = Files.readString(javaVersionFile).trim()
                JdkVersion.fromString(version)
            } catch (e: Exception) {
                JdkVersion.JDK_21
            }
        }

        return JdkVersion.JDK_21 // default
    }

    /**
     * Detects all available JDK versions installed on the system.
     * This is useful for determining which JDKs can be used for analysis.
     */
    fun detectInstalledJdks(): List<JdkVersion> {
        val installed = mutableListOf<JdkVersion>()
        
        // Check JAVA_HOME
        val javaHome = System.getenv("JAVA_HOME")
        if (javaHome != null) {
            val version = detectJavaHomeVersion(javaHome)
            if (version != JdkVersion.UNKNOWN) {
                installed.add(version)
            }
        }

        // Check common installation paths on different OS
        val commonPaths = listOf(
            // Linux/macOS
            "/usr/lib/jvm",
            "/Library/Java/JavaVirtualMachines",
            // Windows
            "C:\\Program Files\\Java",
            "C:\\Program Files (x86)\\Java"
        )

        for (path in commonPaths) {
            try {
                val jvmDir = Path.of(path)
                if (Files.exists(jvmDir)) {
                    Files.list(jvmDir).use { stream ->
                        stream.filter { Files.isDirectory(it) }
                            .map { detectJavaHomeVersion(it.toString()) }
                            .filter { it != JdkVersion.UNKNOWN }
                            .distinct()
                            .forEach { installed.add(it) }
                    }
                }
            } catch (e: Exception) {
                // Skip paths that don't exist or aren't accessible
            }
        }

        return installed.distinct().sortedByDescending { it.major }
    }

    private fun detectJavaHomeVersion(javaHome: String): JdkVersion {
        return try {
            val releaseFile = Path.of(javaHome, "release")
            if (Files.exists(releaseFile)) {
                val content = Files.readString(releaseFile)
                val versionMatch = Regex("""JAVA_VERSION="([^"]+)""").find(content)
                if (versionMatch != null) {
                    return JdkVersion.fromString(versionMatch.groupValues[1])
                }
            }

            // Fallback: try to run java -version
            val javaExe = Path.of(javaHome, "bin", "java").toString()
            val process = ProcessBuilder(javaExe, "-version").start()
            val error = process.errorStream.bufferedReader().readText()
            val versionMatch = Regex("""version\s+([\d.]+)""").find(error)
            if (versionMatch != null) {
                return JdkVersion.fromString(versionMatch.groupValues[1])
            }

            JdkVersion.UNKNOWN
        } catch (e: Exception) {
            JdkVersion.UNKNOWN
        }
    }
}