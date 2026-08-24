package dev.repomind.core.eval

import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.language.java.JavaSemanticParser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class FixtureEvalTest {

    @Test
    fun `harness scores real parser output against json case file`() {
        val root = Files.createTempDirectory("repomind-eval")
        val src = root.resolve("src/main/java")

        fun javaSource(relPath: String, content: String) {
            val file = src.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }

        javaSource("com/example/Repo.java", "package com.example;\npublic interface Repo {\n    void save();\n}\n")
        javaSource("com/example/DbRepo.java", "package com.example;\npublic class DbRepo implements Repo {\n    public void save() {}\n}\n")
        javaSource(
            "com/example/Service.java",
            """
            package com.example;
            import com.example.Repo;

            public class Service {
                private final Repo repo;
                public Service(Repo repo) { this.repo = repo; }
                public void handle() {
                    repo.save();
                    new DbRepo().save();
                }
            }
            """.trimIndent(),
        )

        val module = RepoModule(name = "eval-module", path = root, buildFile = null, sourceRoots = listOf(SourceRoot(src, isTest = false)))
        val parsed = JavaSemanticParser().parseModule(module, emptyList())

        val casesPath = Files.createTempFile("cases", ".json")
        casesPath.writeText(
            """
            [
              {
                "name": "service-calls-repo-interface",
                "expectations": [
                  {"sourceFqn": "com.example.Service", "targetOwner": "com.example.Repo", "minConfidence": "CONFIRMED"}
                ]
              },
              {
                "name": "service-attributed-to-single-impl",
                "expectations": [
                  {"sourceFqn": "com.example.Service", "targetOwner": "com.example.DbRepo"}
                ]
              },
              {
                "name": "service-constructs-dbrepo",
                "expectations": [
                  {"sourceFqn": "com.example.Service", "targetOwner": "com.example.DbRepo", "kind": "CALLS"}
                ]
              }
            ]
            """.trimIndent(),
        )

        val report = EvalHarness().run(parsed.edges, CaseLoader.load(casesPath))

        assertEquals(3, report.caseResults.size)
        for (result in report.caseResults) {
            assertEquals(0, result.missed, "case ${result.caseName} missed: ${result.missedDetails}")
        }
        assertEquals(1.0, report.macroRecall)
    }
}
