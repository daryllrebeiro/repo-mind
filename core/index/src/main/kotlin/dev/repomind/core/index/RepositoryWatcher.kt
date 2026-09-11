package dev.repomind.core.index

import java.io.Closeable
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background file watcher daemon that observes source repository directories,
 * coalesces rapid filesystem events with a debounce window, and triggers
 * incremental indexing updates without blocking or looping indefinitely.
 */
class RepositoryWatcher(
    private val repoRoot: Path,
    private val indexer: IncrementalIndexer,
    private val debounceMs: Long = 300L,
    private val onUpdate: (IncrementalResult) -> Unit = {},
) : Closeable {

    private val normalizedRoot = repoRoot.toAbsolutePath().normalize()
    private val running = AtomicBoolean(false)
    private var watchService: WatchService? = null
    private val watchKeys = mutableMapOf<WatchKey, Path>()

    companion object {
        private val IGNORED_DIRS = setOf(
            ".git",
            ".repomind",
            ".gradle",
            ".idea",
            "build",
            "target",
            "bin",
            "out",
            "node_modules",
            ".vscode",
        )

        private val WATCHED_EXTENSIONS = setOf(
            "java",
            "kt",
            "kts",
            "xml",
            "yaml",
            "yml",
            "properties",
            "gradle",
        )
    }

    fun isIgnored(path: Path): Boolean {
        val abs = path.toAbsolutePath().normalize()
        if (!abs.startsWith(normalizedRoot)) return true
        val rel = normalizedRoot.relativize(abs)
        for (part in rel) {
            val name = part.toString()
            if (name in IGNORED_DIRS || (name.startsWith(".") && name != "." && name != "..")) {
                return true
            }
        }
        return false
    }

    private fun registerTree(start: Path, ws: WatchService) {
        if (!Files.exists(start)) return
        Files.walkFileTree(
            start,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isIgnored(dir)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val key = dir.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                    watchKeys[key] = dir
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * Starts watching for filesystem events.
     * @param maxIterations If > 0, the watcher stops after triggering this many updates (useful for testing).
     * @param timeoutMs If > 0, the watcher stops after this many milliseconds have elapsed.
     */
    fun start(maxIterations: Int = -1, timeoutMs: Long = -1L) {
        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        running.set(true)
        registerTree(normalizedRoot, ws)

        var hasPending = false
        var lastEventTime = 0L
        var completedUpdates = 0
        val startTime = System.currentTimeMillis()

        while (running.get()) {
            val now = System.currentTimeMillis()
            if (timeoutMs > 0 && (now - startTime >= timeoutMs)) {
                running.set(false)
                break
            }

            val key = ws.poll(100, TimeUnit.MILLISECONDS)

            if (key != null) {
                val dir = watchKeys[key]
                if (dir != null) {
                    for (event in key.pollEvents()) {
                        val kind = event.kind()
                        if (kind == OVERFLOW) continue

                        @Suppress("UNCHECKED_CAST")
                        val ev = event as java.nio.file.WatchEvent<Path>
                        val resolved = dir.resolve(ev.context())

                        if (kind == ENTRY_CREATE && Files.isDirectory(resolved) && !isIgnored(resolved)) {
                            registerTree(resolved, ws)
                            hasPending = true
                            lastEventTime = now
                        } else {
                            val ext = resolved.fileName?.toString()?.substringAfterLast('.', "")?.lowercase().orEmpty()
                            if (ext in WATCHED_EXTENSIONS && !isIgnored(resolved)) {
                                hasPending = true
                                lastEventTime = now
                            }
                        }
                    }
                }
                key.reset()
            }

            if (hasPending && (now - lastEventTime >= debounceMs)) {
                try {
                    val result = indexer.update(normalizedRoot)
                    completedUpdates++
                    onUpdate(result)
                } catch (e: Exception) {
                    System.err.println("WARN: incremental index error during watch: ${e.message}")
                }
                hasPending = false

                if (maxIterations > 0 && completedUpdates >= maxIterations) {
                    running.set(false)
                    break
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            watchService?.close()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        stop()
    }
}
