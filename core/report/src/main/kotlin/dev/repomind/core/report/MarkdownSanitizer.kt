package dev.repomind.core.report

object MarkdownSanitizer {

    private val controlChars = Regex("[\\x00-\\x1f\\x7f]")

    /**
     * Identifiers from analyzed repositories are untrusted; they must not be able to
     * break out of markdown code spans, headings, or table cells.
     */
    fun sanitize(raw: String): String =
        controlChars.replace(raw, " ")
            .replace('`', '\'')
            .replace('|', '/')
            .replace("<", "(")
            .replace(">", ")")
            .trim()
            .take(200)
}
