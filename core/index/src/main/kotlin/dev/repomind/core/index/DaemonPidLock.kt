package dev.repomind.core.index

import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

class DaemonAlreadyRunningException(val pid: Long, message: String) : RuntimeException(message)

/**
 * Manages the `.repomind/daemon.pid` file to guarantee that at most one
 * RepoMind daemon instance runs per repository path.
 */
class DaemonPidLock(private val repomindDir: Path) : Closeable {

    private val pidFile = repomindDir.resolve("daemon.pid")
    private var acquired = false

    fun acquire(): Long {
        Files.createDirectories(repomindDir)
        if (Files.isRegularFile(pidFile)) {
            val existingPidStr = Files.readString(pidFile).trim()
            val existingPid = existingPidStr.toLongOrNull()
            if (existingPid != null && isProcessAlive(existingPid)) {
                throw DaemonAlreadyRunningException(
                    existingPid,
                    "RepoMind daemon is already running for repository (PID $existingPid)",
                )
            } else {
                // Stale PID file; clean it up
                Files.deleteIfExists(pidFile)
            }
        }

        val currentPid = ProcessHandle.current().pid()
        Files.writeString(pidFile, currentPid.toString())
        acquired = true
        return currentPid
    }

    private fun isProcessAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    fun release() {
        if (acquired) {
            try {
                Files.deleteIfExists(pidFile)
            } catch (_: Exception) {
            }
            acquired = false
        }
    }

    override fun close() {
        release()
    }
}
