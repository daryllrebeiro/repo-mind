package dev.repomind.core.index.remote

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

interface RemoteIndexStorage {
    fun push(key: String, bundleFile: Path): String
    fun pull(key: String, destinationFile: Path): Boolean

    companion object {
        fun resolve(endpoint: String, authToken: String? = null): RemoteIndexStorage {
            val trimmed = endpoint.trim()
            return when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    HttpRemoteStorage(trimmed, authToken)
                trimmed.startsWith("file://") ->
                    DirectoryRemoteStorage(Path.of(URI(trimmed)))
                else ->
                    DirectoryRemoteStorage(Path.of(trimmed))
            }
        }
    }
}

class DirectoryRemoteStorage(val rootDir: Path) : RemoteIndexStorage {

    init {
        Files.createDirectories(rootDir)
    }

    override fun push(key: String, bundleFile: Path): String {
        val target = rootDir.resolve("$key.repomind")
        Files.createDirectories(target.parent ?: rootDir)
        Files.copy(bundleFile, target, StandardCopyOption.REPLACE_EXISTING)
        return target.toAbsolutePath().normalize().toString()
    }

    override fun pull(key: String, destinationFile: Path): Boolean {
        val source = rootDir.resolve("$key.repomind")
        if (!Files.isRegularFile(source)) return false
        Files.createDirectories(destinationFile.parent ?: Path.of("."))
        Files.copy(source, destinationFile, StandardCopyOption.REPLACE_EXISTING)
        return true
    }
}

class HttpRemoteStorage(
    val baseUrl: String,
    val authToken: String? = null,
    val timeout: Duration = Duration.ofSeconds(30),
) : RemoteIndexStorage {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun push(key: String, bundleFile: Path): String {
        val uri = URI.create("${baseUrl.trimEnd('/')}/$key.repomind")
        val requestBuilder = HttpRequest.newBuilder(uri)
            .PUT(HttpRequest.BodyPublishers.ofFile(bundleFile))
            .header("Content-Type", "application/zip")
            .timeout(timeout)

        if (authToken != null) {
            requestBuilder.header("Authorization", "Bearer $authToken")
        }

        val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("Failed to push remote index bundle to $uri: HTTP ${response.statusCode()}")
        }
        return uri.toString()
    }

    override fun pull(key: String, destinationFile: Path): Boolean {
        val uri = URI.create("${baseUrl.trimEnd('/')}/$key.repomind")
        val requestBuilder = HttpRequest.newBuilder(uri)
            .GET()
            .timeout(timeout)

        if (authToken != null) {
            requestBuilder.header("Authorization", "Bearer $authToken")
        }

        val response = try {
            client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: Exception) {
            return false
        }

        if (response.statusCode() != 200) {
            return false
        }

        Files.createDirectories(destinationFile.parent ?: Path.of("."))
        response.body().use { stream ->
            FileOutputStream(destinationFile.toFile()).use { out ->
                stream.copyTo(out)
            }
        }
        return true
    }
}
