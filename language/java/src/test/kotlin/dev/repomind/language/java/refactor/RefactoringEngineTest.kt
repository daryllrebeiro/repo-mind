package dev.repomind.language.java.refactor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefactoringEngineTest {

    @Test
    fun `removes dead private method while preserving comments and indentation`() {
        val original = """
            package com.example;

            // Service comment
            public class UserService {

                // Important active method
                public void activeMethod() {
                    System.out.println("active");
                }

                // Unused dead helper
                private void deadHelper() {
                    System.out.println("dead");
                }
            }
        """.trimIndent()

        val (modified, removed) = AstRefactoringEngine.removeDeadMethods(original, setOf("deadHelper"))
        assertEquals(listOf("deadHelper"), removed)
        assertFalse(modified.contains("deadHelper"))
        assertTrue(modified.contains("activeMethod"))
        assertTrue(modified.contains("// Important active method"))
        assertTrue(modified.contains("// Service comment"))
    }

    @Test
    fun `replaces deprecated method call expressions cleanly`() {
        val original = """
            package com.example;

            public class Client {
                public void run() {
                    service.oldMethod();
                    other.oldMethod();
                }
            }
        """.trimIndent()

        val (modified, count) = AstRefactoringEngine.replaceMethodCalls(original, "oldMethod", "newMethod")
        assertEquals(2, count)
        assertTrue(modified.contains("service.newMethod();"))
        assertTrue(modified.contains("other.newMethod();"))
        assertFalse(modified.contains("oldMethod"))
    }

    @Test
    fun `updates package declaration and caller imports`() {
        val originalClass = """
            package com.example.oldpkg;

            public class MovedService {
            }
        """.trimIndent()

        val updatedClass = AstRefactoringEngine.updatePackageDeclaration(originalClass, "com.example.newpkg")
        assertTrue(updatedClass.contains("package com.example.newpkg;"))
        assertFalse(updatedClass.contains("com.example.oldpkg"))

        val caller = """
            package com.example.consumer;

            import com.example.oldpkg.MovedService;

            public class Consumer {
                private MovedService service;
            }
        """.trimIndent()

        val (updatedCaller, changed) = AstRefactoringEngine.updateImports(
            caller,
            "com.example.oldpkg.MovedService",
            "com.example.newpkg.MovedService",
        )
        assertTrue(changed)
        assertTrue(updatedCaller.contains("import com.example.newpkg.MovedService;"))
        assertFalse(updatedCaller.contains("import com.example.oldpkg.MovedService;"))
    }

    @Test
    fun `generates valid unified git diff patch`() {
        val orig = "line 1\nold line\nline 3\n"
        val mod = "line 1\nnew line\nline 3\n"
        val diff = AstRefactoringEngine.generateUnifiedDiff("src/Test.java", orig, mod)

        assertTrue(diff.startsWith("--- a/src/Test.java"))
        assertTrue(diff.contains("+++ b/src/Test.java"))
        assertTrue(diff.contains("-old line"))
        assertTrue(diff.contains("+new line"))
    }
}
