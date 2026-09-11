package dev.repomind.core.index

import dev.repomind.storage.sqlite.SymbolDatabase
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class HttpEndpointDefinition(
    val repoName: String,
    val serviceName: String,
    val httpMethod: String,
    val path: String,
    val declaringFqn: String,
    val memberName: String,
    val isClient: Boolean, // true for Feign/REST client, false for Controller endpoint
)

@Serializable
data class CrossRepoLink(
    val clientRepo: String,
    val clientFqn: String,
    val targetRepo: String,
    val targetFqn: String,
    val httpMethod: String,
    val path: String,
    val confidence: String = "CONFIRMED",
)

@Serializable
data class FederatedBlastRadius(
    val targetSymbol: String,
    val targetRepo: String,
    val callingClients: List<String>,
    val callingRepos: List<String>,
    val totalAffectedCallers: Int,
)

@Serializable
data class FederationReport(
    val linkedRepos: List<String>,
    val totalEndpointsDiscovered: Int,
    val crossRepoLinks: List<CrossRepoLink>,
    val crossRepoBlastRadii: List<FederatedBlastRadius>,
) {
    fun toMarkdown(): String {
        val sb = StringBuilder()
        sb.appendLine("# RepoMind Enterprise Polyrepo Federation Report")
        sb.appendLine()
        sb.appendLine("- **Linked Repositories**: ${linkedRepos.joinToString(", ") { "`$it`" }}")
        sb.appendLine("- **Total Endpoints Discovered**: $totalEndpointsDiscovered")
        sb.appendLine("- **Cross-Repo API Contracts**: ${crossRepoLinks.size}")
        sb.appendLine()

        sb.appendLine("## Cross-Repository API Dependencies")
        sb.appendLine()
        if (crossRepoLinks.isEmpty()) {
            sb.appendLine("*(No cross-repository Feign or REST client couplings discovered between the provided repositories)*")
        } else {
            sb.appendLine("| Client Repo | Client Interface | Target Repo | Target Controller | Method | Path |")
            sb.appendLine("|---|---|---|---|---|---|")
            for (link in crossRepoLinks) {
                sb.appendLine("| `${link.clientRepo}` | `${link.clientFqn}` | `${link.targetRepo}` | `${link.targetFqn}` | **${link.httpMethod}** | `${link.path}` |")
            }
        }
        sb.appendLine()

        sb.appendLine("## Cross-Service Blast Radius Matrix")
        sb.appendLine()
        if (crossRepoBlastRadii.isEmpty()) {
            sb.appendLine("*(No downstream cross-repository impact discovered)*")
        } else {
            sb.appendLine("| Target Service API | Upstream Calling Repos | Calling Clients Count | Calling Components |")
            sb.appendLine("|---|---|---|---|")
            for (radius in crossRepoBlastRadii) {
                val repos = radius.callingRepos.joinToString(", ") { "`$it`" }
                val clients = radius.callingClients.take(3).joinToString("<br>") { "`$it`" }
                sb.appendLine("| `${radius.targetSymbol}` (`${radius.targetRepo}`) | $repos | ${radius.totalAffectedCallers} | $clients |")
            }
        }
        return sb.toString()
    }
}

class PolyrepoFederationEngine {

    /**
     * Mines REST endpoints (controllers) and clients (Feign) across multiple repositories,
     * resolving inter-service communication edges and cross-repository blast radii.
     */
    fun federate(repoRoots: List<Path>): FederationReport {
        val linkedRepos = mutableListOf<String>()
        val endpoints = mutableListOf<HttpEndpointDefinition>()
        val repoDatabases = mutableMapOf<String, SymbolDatabase>()

        try {
            for (root in repoRoots) {
                val normalized = root.toAbsolutePath().normalize()
                val repoName = normalized.fileName.toString()
                val dbPath = normalized.resolve(".repomind/index.db")
                if (!Files.isRegularFile(dbPath)) continue

                linkedRepos.add(repoName)
                val db = SymbolDatabase.open(dbPath, readOnly = true)
                repoDatabases[repoName] = db

                endpoints.addAll(extractEndpoints(repoName, db))
            }

            val clientEndpoints = endpoints.filter { it.isClient }
            val controllerEndpoints = endpoints.filter { !it.isClient }

            val crossRepoLinks = mutableListOf<CrossRepoLink>()

            for (client in clientEndpoints) {
                // Match against controller endpoints
                val matched = controllerEndpoints.firstOrNull { ctrl ->
                    ctrl.repoName != client.repoName &&
                        matchServices(client.serviceName, ctrl.serviceName, ctrl.repoName) &&
                        ctrl.httpMethod.equals(client.httpMethod, ignoreCase = true) &&
                        normalizePath(ctrl.path) == normalizePath(client.path)
                } ?: controllerEndpoints.firstOrNull { ctrl ->
                    ctrl.repoName != client.repoName &&
                        ctrl.httpMethod.equals(client.httpMethod, ignoreCase = true) &&
                        normalizePath(ctrl.path) == normalizePath(client.path)
                }

                if (matched != null) {
                    crossRepoLinks.add(
                        CrossRepoLink(
                            clientRepo = client.repoName,
                            clientFqn = "${client.declaringFqn}#${client.memberName}",
                            targetRepo = matched.repoName,
                            targetFqn = "${matched.declaringFqn}#${matched.memberName}",
                            httpMethod = client.httpMethod,
                            path = client.path,
                        )
                    )
                }
            }

            // Group links by target controller to compute cross-service blast radius
            val blastRadii = crossRepoLinks.groupBy { it.targetFqn }.map { (targetFqn, links) ->
                val targetRepo = links.first().targetRepo
                val callingClients = links.map { it.clientFqn }.distinct()
                val callingRepos = links.map { it.clientRepo }.distinct()

                // Also find in-repo callers of the calling client interface
                var totalCallers = callingClients.size
                for (link in links) {
                    val clientDb = repoDatabases[link.clientRepo]
                    if (clientDb != null) {
                        val clientOwner = link.clientFqn.substringBefore('#')
                        val inRepoCallers = clientDb.edges.findDirectCallers(clientOwner).size
                        totalCallers += inRepoCallers
                    }
                }

                FederatedBlastRadius(
                    targetSymbol = targetFqn,
                    targetRepo = targetRepo,
                    callingClients = callingClients,
                    callingRepos = callingRepos,
                    totalAffectedCallers = totalCallers,
                )
            }.sortedByDescending { it.totalAffectedCallers }

            return FederationReport(
                linkedRepos = linkedRepos,
                totalEndpointsDiscovered = endpoints.size,
                crossRepoLinks = crossRepoLinks,
                crossRepoBlastRadii = blastRadii,
            )
        } finally {
            repoDatabases.values.forEach { it.close() }
        }
    }

    private fun extractEndpoints(repoName: String, db: SymbolDatabase): List<HttpEndpointDefinition> {
        val results = mutableListOf<HttpEndpointDefinition>()
        val types = db.allTypes()

        for (type in types) {
            val isFeignClient = type.annotations.any { it.contains("FeignClient", ignoreCase = true) }
            val isController = type.annotations.any { it.contains("Controller", ignoreCase = true) }

            if (isFeignClient) {
                val serviceName = extractServiceName(type.annotations) ?: repoName
                val basePath = extractPath(type.annotations).orEmpty()
                val methods = db.findByParentFqn(type.qualifiedName).filter { it.kind == "METHOD" }

                for (method in methods) {
                    val (httpMethod, methodPath) = extractMethodEndpoint(method.annotations)
                    if (httpMethod != null) {
                        results.add(
                            HttpEndpointDefinition(
                                repoName = repoName,
                                serviceName = serviceName,
                                httpMethod = httpMethod,
                                path = combinePaths(basePath, methodPath),
                                declaringFqn = type.qualifiedName,
                                memberName = method.name,
                                isClient = true,
                            )
                        )
                    }
                }
            } else if (isController) {
                val basePath = extractPath(type.annotations).orEmpty()
                val methods = db.findByParentFqn(type.qualifiedName).filter { it.kind == "METHOD" }

                for (method in methods) {
                    val (httpMethod, methodPath) = extractMethodEndpoint(method.annotations)
                    if (httpMethod != null) {
                        results.add(
                            HttpEndpointDefinition(
                                repoName = repoName,
                                serviceName = repoName,
                                httpMethod = httpMethod,
                                path = combinePaths(basePath, methodPath),
                                declaringFqn = type.qualifiedName,
                                memberName = method.name,
                                isClient = false,
                            )
                        )
                    }
                }
            }
        }
        return results
    }

    private fun extractServiceName(annotations: List<String>): String? {
        for (ann in annotations) {
            val match = Regex("""(?:name|value)\s*=\s*["']([^"']+)["']""").find(ann)
                ?: Regex("""@FeignClient\s*\(\s*["']([^"']+)["']""").find(ann)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractPath(annotations: List<String>): String? {
        for (ann in annotations) {
            val match = Regex("""(?:path|value)\s*=\s*["']([^"']+)["']""").find(ann)
                ?: Regex("""@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\s*\(\s*["']([^"']+)["']""").find(ann)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private fun extractMethodEndpoint(annotations: List<String>): Pair<String?, String> {
        var httpMethod: String? = null
        var path = ""

        for (ann in annotations) {
            if (httpMethod == null) {
                httpMethod = when {
                    ann.contains("GetMapping", ignoreCase = true) -> "GET"
                    ann.contains("PostMapping", ignoreCase = true) -> "POST"
                    ann.contains("PutMapping", ignoreCase = true) -> "PUT"
                    ann.contains("DeleteMapping", ignoreCase = true) -> "DELETE"
                    ann.contains("PatchMapping", ignoreCase = true) -> "PATCH"
                    ann.contains("RequestMapping", ignoreCase = true) -> {
                        val methodMatch = Regex("""method\s*=\s*RequestMethod\.([A-Z]+)""").find(ann)
                        methodMatch?.groupValues?.get(1) ?: "GET"
                    }
                    else -> null
                }
            }
            val extractedPath = extractPath(listOf(ann))
            if (!extractedPath.isNullOrBlank()) {
                path = extractedPath
            }
        }
        return httpMethod to path
    }

    private fun combinePaths(base: String, sub: String): String {
        val b = "/" + base.trim('/')
        val s = "/" + sub.trim('/')
        val full = (if (b == "/") "" else b) + (if (s == "/") "" else s)
        return if (full.isEmpty()) "/" else full
    }

    private fun normalizePath(path: String): String {
        // Normalizes path parameters: e.g. /orders/{id} and /orders/{orderId} to /orders/{*}
        return path.replace(Regex("""\{[^}]+}"""), "{*}")
            .replace(Regex("""/"""), "/")
            .trimEnd('/')
            .ifEmpty { "/" }
    }

    private fun matchServices(clientTargetService: String, controllerServiceName: String, controllerRepoName: String): Boolean {
        val c = clientTargetService.lowercase().replace("-", "").replace("_", "")
        val s = controllerServiceName.lowercase().replace("-", "").replace("_", "")
        val r = controllerRepoName.lowercase().replace("-", "").replace("_", "")
        return c == s || c == r || s.contains(c) || r.contains(c)
    }
}
