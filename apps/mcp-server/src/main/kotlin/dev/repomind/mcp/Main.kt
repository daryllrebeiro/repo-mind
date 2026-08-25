package dev.repomind.mcp

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val dispatcher = McpDispatcher()
    val stdout = System.out.bufferedWriter()
    try {
        while (true) {
            val line = readLine() ?: break
            val response = dispatcher.handle(line) ?: continue
            stdout.write(response)
            stdout.newLine()
            stdout.flush()
        }
    } catch (_: java.io.IOError) {
    }
    exitProcess(0)
}
