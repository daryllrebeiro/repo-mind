package dev.repomind.core.classpath

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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

    private fun fileFor(key: String): Path = cacheDir.resolve("${sha256(key)}.json")
}

fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
