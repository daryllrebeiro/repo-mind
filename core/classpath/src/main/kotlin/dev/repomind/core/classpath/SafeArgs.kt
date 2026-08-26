package dev.repomind.core.classpath

object SafeArgs {

    private val controlChars = Regex("[\\x00-\\x1f\\x7f]")

    fun validate(arg: String) {
        require(arg.isNotEmpty()) { "empty command argument" }
        require(!controlChars.containsMatchIn(arg)) {
            "command argument contains control characters: ${arg.take(20)}"
        }
    }

    /**
     * Escapes an argument for safe passage through "cmd /c", which Windows
     * ProcessBuilder uses to launch .cmd/.bat files such as mvn.cmd and gradle.bat.
     * Without this, metacharacters in user-controlled paths (& | ^ % etc.) would be
     * interpreted by cmd.exe.
     */
    fun escapeForWindowsCmd(arg: String): String {
        validate(arg)
        if (arg.matches(SAFE_PATTERN)) return arg
        val escaped = arg.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private val SAFE_PATTERN = Regex("[A-Za-z0-9_\\-./\\\\:=,;+@() ]+")
}
