package dev.repomind.cli

import dev.repomind.core.model.BuildSystem
import dev.repomind.core.model.RepoModule
import dev.repomind.core.model.ScanResult
import dev.repomind.core.model.SourceRoot
import kotlinx.serialization.Serializable

@Serializable
data class SourceRootDto(val path: String, val isTest: Boolean) {
    companion object {
        fun from(s: SourceRoot) = SourceRootDto(s.path.toString(), s.isTest)
    }
}

@Serializable
data class RepoModuleDto(
    val name: String,
    val path: String,
    val buildFile: String?,
    val sourceRoots: List<SourceRootDto>,
) {
    companion object {
        fun from(m: RepoModule) = RepoModuleDto(
            name = m.name,
            path = m.path.toString(),
            buildFile = m.buildFile?.toString(),
            sourceRoots = m.sourceRoots.map(SourceRootDto::from),
        )
    }
}

@Serializable
data class ScanResultDto(
    val root: String,
    val buildSystem: BuildSystem,
    val modules: List<RepoModuleDto>,
    val sourceFileCount: Int,
) {
    companion object {
        fun from(r: ScanResult) = ScanResultDto(
            root = r.root.toString(),
            buildSystem = r.buildSystem,
            modules = r.modules.map(RepoModuleDto::from),
            sourceFileCount = r.sourceFileCount,
        )
    }
}
