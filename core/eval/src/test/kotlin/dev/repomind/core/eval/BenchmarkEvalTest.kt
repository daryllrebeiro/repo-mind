package dev.repomind.core.eval

import dev.repomind.core.impact.ImpactAnalyzer
import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.SourceRoot
import dev.repomind.core.model.code.Confidence
import dev.repomind.language.java.JavaSemanticParser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BenchmarkEvalTest {

    @Test
    fun `benchmark precision and recall exceeds quality gate thresholds`() {
        val root = Files.createTempDirectory("benchmark-eval")
        val mainSrc = root.resolve("src/main/java")
        val testSrc = root.resolve("src/test/java")

        fun file(base: java.nio.file.Path, relPath: String, content: String) {
            val file = base.resolve(relPath)
            Files.createDirectories(file.parent)
            file.writeText(content)
        }

        // Domain & Persistence
        file(
            mainSrc,
            "com/bank/account/AccountRepository.java",
            """
            package com.bank.account;
            import org.springframework.stereotype.Repository;

            @Repository
            public interface AccountRepository {
                void save(String accountId, long balance);
                long findBalance(String accountId);
            }
            """.trimIndent(),
        )

        file(
            mainSrc,
            "com/bank/account/JpaAccountRepository.java",
            """
            package com.bank.account;
            import org.springframework.stereotype.Repository;

            @Repository
            public class JpaAccountRepository implements AccountRepository {
                public void save(String accountId, long balance) {}
                public long findBalance(String accountId) { return 100L; }
            }
            """.trimIndent(),
        )

        // Notification Client
        file(
            mainSrc,
            "com/bank/notify/NotificationClient.java",
            """
            package com.bank.notify;
            import org.springframework.stereotype.Component;

            @Component
            public class NotificationClient {
                public void sendAlert(String accountId, String message) {}
            }
            """.trimIndent(),
        )

        // Service Layer
        file(
            mainSrc,
            "com/bank/account/AccountService.java",
            """
            package com.bank.account;
            import com.bank.notify.NotificationClient;
            import org.springframework.stereotype.Service;

            @Service
            public class AccountService {
                private final AccountRepository repository;
                private final NotificationClient notificationClient;

                public AccountService(AccountRepository repository, NotificationClient notificationClient) {
                    this.repository = repository;
                    this.notificationClient = notificationClient;
                }

                public void deposit(String accountId, long amount) {
                    long current = repository.findBalance(accountId);
                    repository.save(accountId, current + amount);
                    notificationClient.sendAlert(accountId, "Deposited " + amount);
                }
            }
            """.trimIndent(),
        )

        // Presentation Layer
        file(
            mainSrc,
            "com/bank/account/AccountController.java",
            """
            package com.bank.account;
            import org.springframework.web.bind.annotation.RestController;

            @RestController
            public class AccountController {
                private final AccountService accountService;

                public AccountController(AccountService accountService) {
                    this.accountService = accountService;
                }

                public void handleDeposit(String id, long amount) {
                    accountService.deposit(id, amount);
                }
            }
            """.trimIndent(),
        )

        // Test Suite
        file(
            testSrc,
            "com/bank/account/AccountServiceTest.java",
            """
            package com.bank.account;
            import org.junit.jupiter.api.Test;

            public class AccountServiceTest {
                @Test
                public void testDeposit() {
                    JpaAccountRepository repo = new JpaAccountRepository();
                    AccountService service = new AccountService(repo, null);
                    service.deposit("acc1", 50);
                }
            }
            """.trimIndent(),
        )

        val module = RepoModule(
            name = "bank-app",
            path = root,
            buildFile = null,
            sourceRoots = listOf(
                SourceRoot(mainSrc, isTest = false),
                SourceRoot(testSrc, isTest = true),
            ),
        )

        val parsed = JavaSemanticParser().parseModule(module, emptyList())

        // Define Ground Truth Expectations grouped by component
        val benchmarkCases = listOf(
            EvalCase(
                name = "controller-orchestration",
                description = "AccountController delegates to AccountService",
                expectations = listOf(
                    EdgeExpectation(
                        sourceFqn = "com.bank.account.AccountController",
                        targetOwner = "com.bank.account.AccountService",
                        kind = "CALLS",
                        minConfidence = "POSSIBLE",
                    ),
                ),
            ),
            EvalCase(
                name = "service-domain-and-infrastructure",
                description = "AccountService interacts with repository interface, resolved single-impl, and notification client",
                expectations = listOf(
                    EdgeExpectation(
                        sourceFqn = "com.bank.account.AccountService",
                        targetOwner = "com.bank.account.AccountRepository",
                        kind = "CALLS",
                        minConfidence = "CONFIRMED",
                    ),
                    EdgeExpectation(
                        sourceFqn = "com.bank.account.AccountService",
                        targetOwner = "com.bank.account.JpaAccountRepository",
                        kind = "CALLS",
                        minConfidence = "POSSIBLE",
                    ),
                    EdgeExpectation(
                        sourceFqn = "com.bank.account.AccountService",
                        targetOwner = "com.bank.notify.NotificationClient",
                        kind = "CALLS",
                        minConfidence = "CONFIRMED",
                    ),
                ),
            ),
            EvalCase(
                name = "test-suite-coverage",
                description = "AccountServiceTest instantiates and tests AccountService and JpaAccountRepository",
                expectations = listOf(
                    EdgeExpectation(
                        sourceFqn = "com.bank.account.AccountServiceTest",
                        targetOwner = "com.bank.account.AccountService",
                        kind = "CALLS",
                        minConfidence = "CONFIRMED",
                    ),
                    EdgeExpectation(
                        sourceFqn = "com.bank.account.AccountServiceTest",
                        targetOwner = "com.bank.account.JpaAccountRepository",
                        kind = "CALLS",
                        minConfidence = "CONFIRMED",
                    ),
                ),
            ),
        )

        val report = EvalHarness().run(parsed.edges, benchmarkCases)

        println("Benchmark Report: ${report.summary()}")
        for (caseResult in report.caseResults) {
            println(" - Case ${caseResult.caseName}: precision=${caseResult.precision} recall=${caseResult.recall} missed=${caseResult.missed}")
        }

        // Quality Gate Verification
        assertTrue(
            report.macroPrecision >= 0.85,
            "Macro precision must be >= 0.85, but was ${report.macroPrecision}",
        )
        assertTrue(
            report.macroRecall >= 0.90,
            "Macro recall must be >= 0.90, but was ${report.macroRecall}",
        )
        assertTrue(report.passedGate, "Quality gate passedGate flag must be true")

        // Impact Analysis Verification on Ground Truth
        val graph = dev.repomind.core.graph.InMemoryGraph(parsed.edges)
        val impact = ImpactAnalyzer(graph).analyze(
            symbolFqn = "com.bank.account.AccountRepository",
            meta = dev.repomind.core.impact.SymbolMeta(
                kind = "INTERFACE",
                visibility = "PUBLIC",
                annotations = listOf("Repository"),
            ),
        )

        assertTrue(
            impact.certainCallers.any { it.startsWith("com.bank.account.AccountService") } ||
                impact.possibleCallers.any { it.startsWith("com.bank.account.AccountService") },
            "Impact of AccountRepository must include AccountService, got certain=${impact.certainCallers}, possible=${impact.possibleCallers}",
        )
        assertTrue(impact.affectedTests.any { it.startsWith("com.bank.account.AccountServiceTest") })
        assertTrue(impact.blastRadius.affectedMethods >= 1 || impact.blastRadius.affectedClasses >= 1)
    }
}
