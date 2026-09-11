package dev.repomind.core.index

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import dev.repomind.core.model.code.EdgeKind
import dev.repomind.storage.sqlite.SymbolDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonServerTest {

    @Test
    fun `pid lock prevents duplicate daemon acquisition and releases cleanly`() {
        val repomindDir = Files.createTempDirectory("repomind-pid-test")
        val lock1 = DaemonPidLock(repomindDir)
        val pid1 = lock1.acquire()
        assertTrue(pid1 > 0)

        val lock2 = DaemonPidLock(repomindDir)
        val ex = assertThrows<DaemonAlreadyRunningException> {
            lock2.acquire()
        }
        assertEquals(pid1, ex.pid)

        lock1.release()
        assertFalse(Files.exists(repomindDir.resolve("daemon.pid")))

        // Can re-acquire after release
        val pid3 = lock2.acquire()
        assertEquals(ProcessHandle.current().pid(), pid3)
        lock2.release()
    }

    @Test
    fun `daemon server starts, handles queries via client, and shuts down cleanly`() {
        val dir = Files.createTempDirectory("daemon-ipc-test")
        val repomindDir = dir.resolve(".repomind")
        val dbPath = repomindDir.resolve("index.db")

        // Populate sample database
        SymbolDatabase.open(dbPath).use { db ->
            db.edges.replaceModule(
                "mod",
                listOf(
                    DependencyEdge("com.service.OrderService", "com.repo.OrderRepo", EdgeKind.CALLS, Confidence.CONFIRMED),
                    DependencyEdge("com.controller.OrderController", "com.service.OrderService", EdgeKind.CALLS, Confidence.CONFIRMED),
                ),
            )
        }

        DaemonServer(repomindDir, dbPath).use { server ->
            server.start()
            assertTrue(server.port > 0)
            assertTrue(Files.isRegularFile(repomindDir.resolve("daemon.port")))

            val client = DaemonClient(repomindDir)
            assertTrue(client.isAvailable())

            val callers = client.queryCallers("com.repo.OrderRepo")
            assertTrue(callers != null)
            assertEquals(listOf("com.controller.OrderController", "com.service.OrderService"), callers.sorted())
        }

        assertFalse(Files.exists(repomindDir.resolve("daemon.port")))
    }
}
