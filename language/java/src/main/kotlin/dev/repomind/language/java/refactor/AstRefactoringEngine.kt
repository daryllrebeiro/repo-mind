package dev.repomind.language.java.refactor

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@Serializable
data class FileDiff(
    val filePath: String,
    val originalContent: String,
    val modifiedContent: String,
    val diffUnified: String,
    val transformationsApplied: List<String>,
)

@Serializable
data class RefactoringResult(
    val totalFilesChanged: Int,
    val totalTransformations: Int,
    val fileDiffs: List<FileDiff>,
    val appliedToDisk: Boolean,
)

object AstRefactoringEngine {

    /**
     * Removes unused/dead methods from source code using LexicalPreservingPrinter
     * to preserve whitespace, comments, and formatting.
     */
    fun removeDeadMethods(
        sourceCode: String,
        deadMethodNames: Set<String>,
        requirePrivate: Boolean = true,
    ): Pair<String, List<String>> {
        if (deadMethodNames.isEmpty() || sourceCode.isBlank()) return sourceCode to emptyList()
        val cu = StaticJavaParser.parse(sourceCode)
        LexicalPreservingPrinter.setup(cu)

        val removed = mutableListOf<String>()
        val methodsToRemove = cu.findAll(MethodDeclaration::class.java).filter { method ->
            method.nameAsString in deadMethodNames && (!requirePrivate || method.isPrivate)
        }

        for (method in methodsToRemove) {
            removed.add(method.nameAsString)
            method.remove()
        }

        val updated = if (removed.isNotEmpty()) LexicalPreservingPrinter.print(cu) else sourceCode
        return updated to removed
    }

    /**
     * Replaces call sites of a deprecated method with the new replacement method name.
     */
    fun replaceMethodCalls(
        sourceCode: String,
        oldMethodName: String,
        newMethodName: String,
    ): Pair<String, Int> {
        if (sourceCode.isBlank() || oldMethodName == newMethodName) return sourceCode to 0
        val cu = StaticJavaParser.parse(sourceCode)
        LexicalPreservingPrinter.setup(cu)

        var count = 0
        val calls = cu.findAll(MethodCallExpr::class.java).filter { it.nameAsString == oldMethodName }
        for (call in calls) {
            call.setName(newMethodName)
            count++
        }

        val updated = if (count > 0) LexicalPreservingPrinter.print(cu) else sourceCode
        return updated to count
    }

    /**
     * Updates package declaration when moving a class between packages.
     */
    fun updatePackageDeclaration(
        sourceCode: String,
        newPackageName: String,
    ): String {
        if (sourceCode.isBlank()) return sourceCode
        val cu = StaticJavaParser.parse(sourceCode)
        LexicalPreservingPrinter.setup(cu)
        cu.setPackageDeclaration(newPackageName)
        return LexicalPreservingPrinter.print(cu)
    }

    /**
     * Updates import statements in caller files from oldFqn to newFqn.
     */
    fun updateImports(
        sourceCode: String,
        oldFqn: String,
        newFqn: String,
    ): Pair<String, Boolean> {
        if (sourceCode.isBlank() || oldFqn == newFqn) return sourceCode to false
        val cu = StaticJavaParser.parse(sourceCode)
        LexicalPreservingPrinter.setup(cu)

        var changed = false
        val imports = cu.imports.filter { it.nameAsString == oldFqn }
        for (imp in imports) {
            imp.setName(newFqn)
            changed = true
        }

        val updated = if (changed) LexicalPreservingPrinter.print(cu) else sourceCode
        return updated to changed
    }

    /**
     * Generates a standard unified diff between original and modified text.
     */
    fun generateUnifiedDiff(filePath: String, original: String, modified: String): String {
        if (original == modified) return ""
        val origLines = original.lines()
        val modLines = modified.lines()

        val sb = StringBuilder()
        val relPath = filePath.replace('\\', '/')
        sb.appendLine("--- a/$relPath")
        sb.appendLine("+++ b/$relPath")

        // Simple line diff chunks
        var i = 0
        var j = 0
        while (i < origLines.size || j < modLines.size) {
            if (i < origLines.size && j < modLines.size && origLines[i] == modLines[j]) {
                i++
                j++
            } else {
                val chunkStartI = i
                val chunkStartJ = j
                val delLines = mutableListOf<String>()
                val addLines = mutableListOf<String>()

                while (i < origLines.size && (j >= modLines.size || origLines[i] != modLines[j])) {
                    delLines.add(origLines[i])
                    i++
                    if (delLines.size > 20) break
                }
                while (j < modLines.size && (i >= origLines.size || origLines.getOrNull(i) != modLines[j])) {
                    addLines.add(modLines[j])
                    j++
                    if (addLines.size > 20) break
                }

                sb.appendLine("@@ -${chunkStartI + 1},${delLines.size} +${chunkStartJ + 1},${addLines.size} @@")
                for (del in delLines) {
                    sb.appendLine("-$del")
                }
                for (add in addLines) {
                    sb.appendLine("+$add")
                }
            }
        }
        return sb.toString()
    }
}
