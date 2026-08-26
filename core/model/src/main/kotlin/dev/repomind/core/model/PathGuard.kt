package dev.repomind.core.model

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object PathGuard {

    fun requireDirectory(root: Path): Path {
        val resolved = root.toAbsolutePath().normalize()
        require(resolved.toFile().isDirectory) { "path is not a directory: $root" }
        return resolved
    }

    /**
     * Resolves [candidate] under [root], refusing escapes via .. or absolute paths,
     * and refusing symlink targets that land outside root.
     */
    fun resolveUnder(root: Path, candidate: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val candidatePath = Path.of(candidate)
        require(!candidatePath.isAbsolute) { "absolute paths are not allowed here: $candidate" }
        val resolved = normalizedRoot.resolve(candidate).normalize()
        require(resolved.startsWith(normalizedRoot)) { "path escapes repository root: $candidate" }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(resolved) || Files.readSymbolicLink(resolved).startsWith(normalizedRoot)) {
                "symbolic link target escapes repository root: $candidate"
            }
        }
        return resolved
    }

    fun requireRegularFile(file: Path, hint: String): Path {
        val resolved = file.toAbsolutePath().normalize()
        require(Files.isRegularFile(resolved)) { "no $hint at $file" }
        return resolved
    }
}
