package dev.repomind.core.classpath

import java.nio.file.Path

data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

fun interface CommandRunner {
    fun run(workDir: Path, command: List<String>): CommandResult
}

object ProcessCommandRunner : CommandRunner {

    override fun run(workDir: Path, command: List<String>): CommandResult {
        val effective = if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("cmd", "/c") + command
        } else {
            command
        }
        val process = ProcessBuilder(effective)
            .directory(workDir.toFile())
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().forEachLine { line -> stdout.appendLine(line) } }
        val errThread = Thread { process.errorStream.bufferedReader().forEachLine { line -> stderr.appendLine(line) } }
        outThread.start()
        errThread.start()
        val exitCode = process.waitFor()
        outThread.join()
        errThread.join()
        return CommandResult(exitCode, stdout.toString(), stderr.toString())
    }
}
