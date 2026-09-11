package dev.repomind.core.classpath

import dev.repomind.core.model.sha256Of
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

interface ClasspathCache {
    fun load(key: String): CachedClasspath?
    fun store(key: String, value: CachedClasspath)
}

class FileBasedClasspathCache(private val cacheDir: Path) : ClasspathCache {

    private val json = Json { prettyPrint = false }

    init {
        Files.createDirectories(cacheDir)
    }

    override fun load(key: String): CachedClasspath? {
        val file = fileFor(key)
        if (!Files.isRegularFile(file)) return null
        return try {
            json.decodeFromString(CachedClasspath.serializer(), Files.readString(file))
        } catch (_: Exception) {
            null
        }
    }

    override fun store(key: String, value: CachedClasspath) {
        val file = fileFor(key)
        Files.writeString(file, json.encodeToString(CachedClasspath.serializer(), value))
    }

    private fun fileFor(key: String): Path = cacheDir.resolve("${sha256Of(key)}.json")
}

