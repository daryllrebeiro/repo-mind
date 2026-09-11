package dev.repomind.core.rules

import dev.repomind.core.model.code.Confidence
import dev.repomind.core.model.code.DependencyEdge
import kotlinx.serialization.Serializable
import kotlin.math.min

@Serializable
data class PackageCycle(
    val path: List<String>,
    val length: Int = if (path.size > 1) path.size - 1 else 0,
    val sampleEdges: List<String> = emptyList(),
)

@Serializable
data class CycleReport(
    val cycles: List<PackageCycle>,
    val totalCycles: Int = cycles.size,
    val packagesInvolved: Set<String> = cycles.flatMap { it.path }.toSet(),
) {
    val hasCycles: Boolean get() = cycles.isNotEmpty()
}

class CycleDetector {

    /**
     * Detects package-level dependency cycles from a collection of dependency edges.
     * Extracts package namespaces from source and target FQNs, builds a package dependency graph,
     * and uses Tarjan's Strongly Connected Components algorithm to find all cyclical package relationships.
     */
    fun detectPackageCycles(
        edges: List<DependencyEdge>,
        minConfidence: Confidence = Confidence.POSSIBLE,
        maxCycles: Int = 100,
    ): CycleReport {
        val packageEdges = mutableMapOf<Pair<String, String>, MutableList<DependencyEdge>>()

        for (edge in edges) {
            if (minConfidence == Confidence.CONFIRMED && edge.confidence != Confidence.CONFIRMED) continue
            val srcPkg = extractPackage(edge.sourceFqn)
            val tgtPkg = extractPackage(edge.targetFqn)
            if (srcPkg.isNotBlank() && tgtPkg.isNotBlank() && srcPkg != tgtPkg) {
                packageEdges.computeIfAbsent(srcPkg to tgtPkg) { mutableListOf() }.add(edge)
            }
        }

        val allPackages = mutableSetOf<String>()
        val adj = mutableMapOf<String, MutableSet<String>>()
        for ((pair, _) in packageEdges) {
            val (src, tgt) = pair
            allPackages.add(src)
            allPackages.add(tgt)
            adj.computeIfAbsent(src) { mutableSetOf() }.add(tgt)
        }

        val sccs = tarjanScc(allPackages, adj)
        val cycles = mutableListOf<PackageCycle>()

        for (scc in sccs) {
            if (cycles.size >= maxCycles) break
            if (scc.size > 1) {
                val sccCycles = findCyclesInComponent(scc, adj, packageEdges, maxCycles - cycles.size)
                cycles.addAll(sccCycles)
            }
        }

        return CycleReport(cycles = cycles)
    }

    private fun extractPackage(fqn: String): String {
        val owner = fqn.substringBefore('#')
        val dotIndex = owner.lastIndexOf('.')
        return if (dotIndex > 0) owner.substring(0, dotIndex) else ""
    }

    private fun tarjanScc(
        vertices: Set<String>,
        adj: Map<String, Set<String>>,
    ): List<Set<String>> {
        var index = 0
        val indices = mutableMapOf<String, Int>()
        val lowLink = mutableMapOf<String, Int>()
        val stack = ArrayDeque<String>()
        val onStack = mutableSetOf<String>()
        val result = mutableListOf<Set<String>>()

        fun strongConnect(v: String) {
            indices[v] = index
            lowLink[v] = index
            index++
            stack.addLast(v)
            onStack.add(v)

            val neighbors = adj[v].orEmpty()
            for (w in neighbors) {
                if (!indices.containsKey(w)) {
                    strongConnect(w)
                    lowLink[v] = min(lowLink[v]!!, lowLink[w]!!)
                } else if (onStack.contains(w)) {
                    lowLink[v] = min(lowLink[v]!!, indices[w]!!)
                }
            }

            if (lowLink[v] == indices[v]) {
                val scc = mutableSetOf<String>()
                while (true) {
                    val w = stack.removeLast()
                    onStack.remove(w)
                    scc.add(w)
                    if (w == v) break
                }
                result.add(scc)
            }
        }

        for (v in vertices) {
            if (!indices.containsKey(v)) {
                strongConnect(v)
            }
        }

        return result
    }

    private fun findCyclesInComponent(
        scc: Set<String>,
        adj: Map<String, Set<String>>,
        edgeMap: Map<Pair<String, String>, List<DependencyEdge>>,
        limit: Int,
    ): List<PackageCycle> {
        val cycles = mutableListOf<PackageCycle>()
        val sccList = scc.toList().sorted()

        for (start in sccList) {
            if (cycles.size >= limit) break
            val visited = mutableSetOf<String>()
            val path = mutableListOf<String>()

            fun dfs(curr: String) {
                if (cycles.size >= limit) return
                visited.add(curr)
                path.add(curr)

                val neighbors = adj[curr].orEmpty().filter { it in scc }.sorted()
                for (next in neighbors) {
                    if (next == start && path.size > 1) {
                        val fullPath = path.toList() + start
                        val samples = formatSampleEdges(fullPath, edgeMap)
                        cycles.add(PackageCycle(path = fullPath, sampleEdges = samples))
                        return
                    }
                    if (next !in visited && next > start) {
                        dfs(next)
                    }
                }

                path.removeAt(path.size - 1)
                visited.remove(curr)
            }

            dfs(start)
        }

        return cycles.distinctBy { it.path.toSet() }
    }

    private fun formatSampleEdges(
        path: List<String>,
        edgeMap: Map<Pair<String, String>, List<DependencyEdge>>,
    ): List<String> {
        val samples = mutableListOf<String>()
        for (i in 0 until path.size - 1) {
            val from = path[i]
            val to = path[i + 1]
            val edges = edgeMap[from to to].orEmpty()
            val sample = edges.firstOrNull()
            if (sample != null) {
                samples.add("${sample.sourceFqn} -> ${sample.targetFqn} [${sample.kind}]")
            } else {
                samples.add("$from -> $to")
            }
        }
        return samples
    }
}
