# RepoMind — Feature List

## Shipped

### Discovery & Resolution
- **Repository scanning** (`repomind scan`) — build-system detection (Maven/Gradle-Groovy/Gradle-KTS), multi-module discovery, source-root mapping, gitignore-aware file counting
- **Classpath resolution** (`repomind classpath`) — real resolved classpaths via each project's own build tool; content-hash cached; loud failures

### Semantic Extraction
- **Semantic Java parsing** (`repomind parse`) — JavaParser + JavaSymbolSolver over resolved classpaths; normalized code model; unresolved symbols tracked honestly
- **Lombok synthesis** — `@Data`, `@Getter/@Setter`, `@Builder`, `@Slf4j` family, constructor annotations; synthetic members flagged
- **Configuration graph** (`repomind config`) — YAML/properties flattening, profile overrides, `@ConfigurationProperties` and `@Value` bindings

### Graph & Persistence
- **Symbol index** (`repomind index`) — SQLite store of types/methods/fields with FQN lookups (<50ms budget verified), annotations, visibility
- **Dependency edges** — IMPORTS / EXTENDS / IMPLEMENTS / USES / CALLS / TESTS with CONFIRMED-vs-POSSIBLE confidence on every edge
- **Call graph** — symbol-solver-resolved invocations, interface dispatch attributed to single implementations, ambiguity never fabricated
- **Test graph** — tests mapped to production code via imports, references, and call-derived attribution
- **Incremental indexing** (`repomind update`) — content-hash change detection, cross-module invalidation, sub-second updates

### Intelligence Layer
- **In-memory traversal** — transitive callers/dependents, affected-tests reachability; 100K-node hub traversal ~58ms (budget 200ms)
- **Impact analysis** (`repomind impact`) — deterministic weighted scoring with evidence-traceable signals and LOW/MEDIUM/HIGH/CRITICAL levels
- **Architecture rules** (`repomind rules`) — YAML stereotype rules (annotations + name patterns); violations feed the impact score
- **Eval harness** (`repomind eval`) — precision/recall against JSON-labeled expectations with confidence-floor semantics

### Interfaces
- **CLI** — one JSON-outputting command per engine capability
- **MCP server** — stdio JSON-RPC exposing find_symbol / find_callers / find_related_tests / analyze_change_impact to AI agents; capped results everywhere

---

## In Progress

### Project Analysis Reports (`repomind report`)
Per-project markdown analysis documents generated entirely from the indexed graph:
- Architecture diagram (Mermaid) derived from real package/type dependencies
- Flow diagrams for key entry points (controllers → services → repositories)
- Hotspot ranking via impact scores
- Points of improvement grounded in measured signals
- Code-smell and risk findings (see [docs/analysis-reports-plan.md](docs/analysis-reports-plan.md))

Status: implementation plan approved; groundwork in progress.

---

## Planned (per InitialPlan.md)

- Phase 12 — security posture hardening (no network during indexing/querying, path traversal guards, no telemetry)
- Phase 13 — fixture regression suite + CI wiring (GitHub Actions: build + tests + eval harness)
- Benchmark validation pass — run eval harness + weight calibration on pinned Spring Boot repos (local clones under `java-examples-for-repo-mind/`, gitignored)
- Phase 14 stretch — VS Code extension, visualization
