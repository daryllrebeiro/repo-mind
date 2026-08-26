package dev.repomind.core.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownSanitizerTest {

    @Test
    fun `control characters are stripped`() {
        assertEquals("a b", MarkdownSanitizer.sanitize("a\nb"))
        assertEquals("tabbed", MarkdownSanitizer.sanitize("\ttabbed"))
    }

    @Test
    fun `markdown structural characters are neutralized`() {
        val sanitized = MarkdownSanitizer.sanitize("`code` | pipe <inject>")
        assertTrue("!".let { !sanitized.contains('`') })
        assertTrue(!sanitized.contains('|'))
        assertTrue(!sanitized.contains('<'))
        assertTrue(!sanitized.contains('>'))
    }

    @Test
    fun `overly long identifiers are capped`() {
        assertEquals(200, MarkdownSanitizer.sanitize("x".repeat(5000)).length)
    }

    @Test
    fun `normal fqns pass through unchanged`() {
        assertEquals(
            "com.example.OrderService",
            MarkdownSanitizer.sanitize("com.example.OrderService"),
        )
    }
}
