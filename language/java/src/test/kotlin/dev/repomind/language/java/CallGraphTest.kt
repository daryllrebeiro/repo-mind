package dev.repomind.language.java

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.EdgeKind
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallGraphTest {

    private fun moduleWithSources(vararg sources: Pair<String, String>): RepoModule {
        val root = Files.createTempDirectory("repomind-calls")
        val src = root.resolve("src/main/java")
        for ((relPath, content) in sources) {
            val file = src.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }
        return RepoModule(name = "m", path = root, buildFile = null, sourceRoots = listOf(SourceRoot(src, isTest = false)))
    }

    @Test
    fun `direct call on a concrete project type is a confirmed calls edge`() {
        val module = moduleWithSources(
            "com/example/Worker.java" to """
                package com.example;
                public class Worker {
                    public int compute() { return 1; }
                }
            """.trimIndent(),
            "com/example/Boss.java" to """
                package com.example;
                public class Boss {
                    private final Worker worker = new Worker();
                    public int run() { return worker.compute(); }
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.CALLS }

        assertTrue(edges.isNotEmpty(), "expected at least one CALLS edge")
        val confirmed = edges.first { it.confidence == Confidence.CONFIRMED && "#compute" in it.targetFqn }
        assertEquals("com.example.Boss", confirmed.sourceFqn)
    }

    @Test
    fun `constructor invocation produces an init calls edge`() {
        val module = moduleWithSources(
            "com/example/Thing.java" to "package com.example;\npublic class Thing {}\n",
            "com/example/Maker.java" to """
                package com.example;
                public class Maker {
                    public void make() {
                        new Thing().toString();
                        Thing t = new Thing();
                    }
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.CALLS && "<init>" in it.targetFqn }

        assertTrue(edges.any { it.sourceFqn == "com.example.Maker" && it.targetFqn == "com.example.Thing#<init>" })
        assertEquals(Confidence.CONFIRMED, edges.single().confidence)
    }

    @Test
    fun `interface field with single implementation yields possible edge to impl`() {
        val module = moduleWithSources(
            "com/example/Repo.java" to "package com.example;\npublic interface Repo {\n    void save();\n}\n",
            "com/example/DbRepo.java" to "package com.example;\npublic class DbRepo implements Repo {\n    public void save() {}\n}\n",
            "com/example/Service.java" to """
                package com.example;
                import com.example.Repo;

                public class Service {
                    private final Repo repo;

                    public Service(Repo repo) { this.repo = repo; }

                    public void handle() { repo.save(); }
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.CALLS && "save" in it.targetFqn }

        assertEquals(2, edges.size, "expected edge to interface (confirmed via resolution) + impl (possible); got $edges")
        val toImpl = edges.first { "DbRepo" in it.targetFqn }
        assertEquals(Confidence.POSSIBLE, toImpl.confidence)
        assertEquals("com.example.Service", toImpl.sourceFqn)
    }

    @Test
    fun `ambiguous interface implementations do not fabricate a single target`() {
        val module = moduleWithSources(
            "com/example/Repo.java" to "package com.example;\npublic interface Repo {\n    void save();\n}\n",
            "com/example/DbRepo.java" to "package com.example;\npublic class DbRepo implements Repo {\n    public void save() {}\n}\n",
            "com/example/CacheRepo.java" to "package com.example;\npublic class CacheRepo implements Repo {\n    public void save() {}\n}\n",
            "com/example/Service.java" to """
                package com.example;
                import com.example.Repo;

                public class Service {
                    private final Repo repo;

                    public Service(Repo repo) { this.repo = repo; }

                    public void handle() { repo.save(); }
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges
            .filter { it.kind == EdgeKind.CALLS && "save" in it.targetFqn && it.sourceFqn == "com.example.Service" }

        assertTrue(edges.none { "DbRepo" in it.targetFqn || "CacheRepo" in it.targetFqn },
            "two impls must not produce a fabricated single-target edge; got $edges")
    }

    @Test
    fun `calls to unknown external types are skipped without crashing`() {
        val module = moduleWithSources(
            "X.java" to """
                import java.util.ArrayList;

                public class X {
                    public void go() {
                        new ArrayList<String>().size();
                        mystery.method();
                    }
                }
            """.trimIndent(),
        )

        val edges = JavaSemanticParser().parseModule(module, emptyList()).edges

        assertTrue(edges.none { it.kind == EdgeKind.CALLS && "mystery" in it.targetFqn },
            "unresolvable call must be skipped, not fabricated; got $edges")
    }
}
