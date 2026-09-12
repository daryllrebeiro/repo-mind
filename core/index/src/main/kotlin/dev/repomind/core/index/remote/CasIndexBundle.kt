package dev.repomind.core.index.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BundleManifest(
    val commitSha: String,
    val contentHash: String,
    val timestamp: Long,
    val moduleCount: Int,
    val symbolCount: Long,
    val edgeCount: Long,
    val repomindVersion: String = "1.0.0",
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Content-Addressable Storage (CAS) packaging for SQLite index databases and metadata.
 */
object CasIndexBundle {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    const val MANIFEST_ENTRY = "manifest.json"
    const val DB_ENTRY = "index.db"

    fun pack(dbPath: Path, manifest: BundleManifest, outputFile: Path): String {
        require(Files.isRegularFile(dbPath)) { "Database file does not exist: $dbPath" }
        Files.createDirectories(outputFile.parent ?: Path.of("."))

        val tempOutput = Files.createTempFile("repomind-bundle-", ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tempOutput.toFile()))).use { zip ->
                // 1. Write manifest.json
                val manifestBytes = json.encodeToString(BundleManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifestBytes)
                zip.closeEntry()

                // 2. Write index.db
                zip.putNextEntry(ZipEntry(DB_ENTRY))
                FileInputStream(dbPath.toFile()).use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }

            Files.move(tempOutput, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return computeFileHash(outputFile)
        } finally {
            Files.deleteIfExists(tempOutput)
        }
    }

    fun unpack(bundleFile: Path, targetDbPath: Path): BundleManifest {
        require(Files.isRegularFile(bundleFile)) { "Bundle file does not exist: $bundleFile" }
        Files.createDirectories(targetDbPath.parent ?: Path.of("."))

        var manifest: BundleManifest? = null
        val tempDb = Files.createTempFile("repomind-extract-", ".db")

        try {
            ZipInputStream(BufferedInputStream(FileInputStream(bundleFile.toFile()))).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            val bytes = zip.readBytes()
                            manifest = json.decodeFromString(BundleManifest.serializer(), String(bytes, Charsets.UTF_8))
                        }
                        DB_ENTRY -> {
                            FileOutputStream(tempDb.toFile()).use { out ->
                                zip.copyTo(out)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val parsedManifest = manifest ?: throw IllegalStateException("Bundle missing manifest.json: $bundleFile")
            Files.move(tempDb, targetDbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return parsedManifest
        } finally {
            Files.deleteIfExists(tempDb)
        }
    }

    fun computeFileHash(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file.toFile())).use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
