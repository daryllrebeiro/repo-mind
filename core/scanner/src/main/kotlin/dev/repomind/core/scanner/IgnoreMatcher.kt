package dev.repomind.core.scanner

import java.io.File
import java.nio.file.Path

class IgnoreMatcher(private val root: Path) {

    private data class Rule(val pattern: String, val negate: Boolean, val dirOnly: Boolean, val anchored: Boolean)

    private val ruleSets = mutableListOf<Pair<Path, List<Rule>>>()
    private val alwaysIgnored = setOf(".git", "node_modules")

    init {
        loadIgnoreFiles(root)
    }

    private fun loadIgnoreFiles(dir: Path) {
        val gitignore = dir.resolve(".gitignore").toFile()
        if (gitignore.isFile) {
            val rules = gitignore.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull(::parseRule)
            if (rules.isNotEmpty()) {
                ruleSets += dir to rules
            }
        }
        dir.toFile().listFiles { f -> f.isDirectory }?.forEach { child ->
            if (!isIgnored(child.toPath().relativizeFromRoot(), true)) {
                loadIgnoreFiles(child.toPath())
            }
        }
    }

    private fun parseRule(line: String): Rule? {
        var l = line
        val negate = l.startsWith("!")
        if (negate) l = l.substring(1)
        val dirOnly = l.endsWith("/")
        if (dirOnly) l = l.removeSuffix("/")
        val anchored = l.startsWith("/")
        if (anchored) l = l.substring(1)
        if (l.isBlank()) return null
        return Rule(l, negate, dirOnly, anchored)
    }

    fun isIgnored(relativePath: Path, isDirectory: Boolean): Boolean {
        val parts = relativePath.toString().replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.any { it in alwaysIgnored }) return true

        var ignored = false
        for ((baseDir, rules) in ruleSets) {
            val relBase = root.relativize(baseDir).toString().replace('\\', '/')
                .split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.size <= relBase.size) continue
            if (relBase.zip(parts).any { (b, p) -> b != p }) continue
            val effectiveParts = parts.drop(relBase.size)
            for (rule in rules) {
                if (rule.dirOnly && !isDirectory && effectiveParts.size == 1) continue
                if (match(rule, effectiveParts)) ignored = !rule.negate
            }
        }
        return ignored
    }

    private fun match(rule: Rule, parts: List<String>): Boolean {
        val patternParts = rule.pattern.split('/')
        return if (patternParts.size > 1) {
            matchSegments(patternParts, 0, parts, 0)
        } else {
            parts.any { globMatch(rule.pattern, it) }
        }
    }

    private fun matchSegments(pattern: List<String>, pi: Int, path: List<String>, vi: Int): Boolean {
        if (pi == pattern.size) return true
        val seg = pattern[pi]
        return when {
            seg == "**" -> (vi..path.size).any { matchSegments(pattern, pi + 1, path, it) }
            vi < path.size && globMatch(seg, path[vi]) -> matchSegments(pattern, pi + 1, path, vi + 1)
            else -> false
        }
    }

    private fun globMatch(pattern: String, value: String): Boolean {
        val sb = StringBuilder()
        for (c in pattern) {
            when (c) {
                '*' -> sb.append("[^/]*")
                '?' -> sb.append("[^/]")
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString()).matches(value)
    }

    private fun Path.relativizeFromRoot(): Path = root.relativize(this)

    companion object {
        fun create(root: Path): IgnoreMatcher = IgnoreMatcher(root)
    }
}
