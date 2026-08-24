package dev.repomind.core.eval

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

object CaseLoader {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(path: Path): List<EvalCase> {
        require(Files.isRegularFile(path)) { "Eval case file not found: $path" }
        return json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(EvalCase.serializer()),
            Files.readString(path),
        )
    }
}
