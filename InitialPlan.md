# RepoMind — Production MVP Implementation Plan (v2)

**Codebase Intelligence for AI Agents**

> Build a small intelligence engine, not a large feature set.
> Given a code change, determine what it can affect and explain why — using an actual repository graph rather than LLM guesswork.

This is a revised, phase-based implementation plan. It keeps the strong parts of the original plan (narrow MVP scope, structural intelligence before AI, local-first security, deterministic scoring) and corrects the places where real-world Java/Spring codebases will break naive assumptions — most importantly, **symbol resolution requires a real classpath, not just an AST**, and **confidence scores need to be validated, not just invented**.

---

## Guiding Principles

1. **Structure before AI.** The core product is a graph. AI is a consumer of that graph, not the engine itself.
2. **Prove correctness on real code early.** Fixtures come later; a handful of real open-source Spring Boot repos are the accuracy benchmark from Week 1.
3. **Confidence is earned, not invented.** Every "confidence" or "risk" score needs a validation loop against real, hand-labeled data before it ships.
4. **Uncertainty is a feature.** "Confirmed vs. Possible" callers is the most defensible, differentiated part of the product — lead with it.
5. **Core must never depend on VS Code, MCP, or any UI layer.** Dependency direction is always UI → API → Core.
6. **Don't build for scale you don't have yet.** No Neo4j, no SaaS, no multi-user auth, no cloud — until the single-repo, local-first engine is proven correct and fast.

---

## What NOT to Build in MVP

- Embeddings / vector database / RAG
- Autonomous coding / multi-agent systems
- Cloud infrastructure / multi-user SaaS
- Enterprise auth, SSO, RBAC
- GitHub SaaS integration, Jira, Slack, Kubernetes
- Distributed indexing
- Fancy architecture visualization (beyond a basic graph view)
- LLM-generated repository summaries
- Neo4j or any dedicated graph database

The MVP question to answer: **Can we build a reliable, semantically-correct structural representation of a real Java/Spring codebase — including its classpath, not just its syntax?**

---

## Target Stack (final — best tool for the job)

The original plan defaulted to Java because the *target* is Java. That conflates the analyzed language with the implementation language — but after re-evaluating, **the JVM is still the right home for the core**, because the product's entire thesis is *semantically-correct Java/Spring resolution*, and true semantic resolution exists only in the JVM ecosystem (Eclipse JDT / JavaParser + JavaSymbolSolver). Hand-building a Java resolver in TS/Rust/Go would spend months replicating what JDT does perfectly.

**Decision: Kotlin (latest stable) on JDK 25 LTS.**

| Component | Technology | Why |
|---|---|---|
| Core language | **Kotlin 2.x** | Null safety, concision, full access to JavaParser/JDT/Maven/Gradle APIs; 100% Java interop with zero friction |
| JDK | **Temurin 25 (LTS)** | Latest LTS; virtual threads for parallel parsing/indexing |
| Build | **Gradle 9.7+ (Kotlin DSL)** | Native Kotlin build scripts, toolchain management, composite builds |
| Parsing | **JavaParser + JavaSymbolSolver** | Semantic resolution wired to real classpath (Phase 2) |
| Precision escalation path | **Eclipse JDT / scip-java** behind the same extractor interface | If JavaSymbolSolver precision proves insufficient, swap extractor without touching query/impact layers |
| Classpath resolution | Shell out to `mvn dependency:build-classpath` / Gradle Tooling API | Real resolved jars, not guessed versions |
| Persistence | **SQLite via sqlite-jdbc** | Zero-config, local-first, single-file store |
| Graph traversal | In-memory adjacency maps hydrated from SQLite | <200ms hub traversal target; no Neo4j |
| CLI | **Picocli** | Mature, GraalVM-native-image friendly |
| Agent interface | **MCP server over stdio JSON-RPC** (kotlinx-serialization for JSON) | Structured output for AI agents; official protocol |
| Tests | **JUnit 5 + Kotest assertions** | JVM standard plus expressive assertions |
| CI | GitHub Actions | Standard |
| Logging | SLF4J + Logback | Standard |
| Static analysis | **detekt + ktlint** | Kotlin-native equivalents of SpotBugs/Checkstyle |

What was rejected and why:
- **TypeScript core**: best MCP SDK and iteration speed, but requires hand-building Java type resolution — the exact failure mode this plan's v2 revision was written to avoid ("symbol resolution requires a real classpath, not just an AST").
- **Rust**: best raw performance, but same symbol-resolution problem as TS with slower iteration.
- **Go**: no advantage for a graph + JVM-analysis product.
- **Plain Java instead of Kotlin**: viable, but Kotlin's null safety and brevity reduce defect surface in exactly the graph-model code where correctness matters most.

The architecture keeps the invariant that makes escalation safe: **the extractor is an interface, not the product**. Phases 3–9 are language-agnostic over a normalized model.

---

## Repository Layout

```text
repomind/
├── apps/
│   ├── cli/
│   ├── mcp-server/
│   └── vscode-extension/        (stretch goal)
├── core/
│   ├── parser/
│   ├── classpath/                ← new: build-system classpath resolution
│   ├── model/
│   ├── extractor/
│   ├── graph/
│   ├── index/
│   ├── query/
│   ├── eval/                     ← new: accuracy/confidence validation harness
│   └── impact/
├── language/
│   └── java/
├── storage/
│   └── sqlite/
├── benchmark-repos/               ← new: 2-3 real OSS Spring Boot repos, pinned
├── tests/
│   ├── fixtures/
│   ├── integration/
│   └── e2e/
├── docs/
├── scripts/
├── .github/workflows/
├── README.md
├── LICENSE
└── CONTRIBUTING.md
```

**Hard rule:** `core/` never imports anything from `apps/`. Dependency direction is always UI → API → Core.

---

## Phase 0 — Product Specification & Benchmark Selection

**Goal:** Define the contract before writing code, and pick the real-world repos that will keep the team honest.

- Write `docs/architecture.md`, `product-spec.md`, `graph-model.md`, `query-api.md`, `mcp-tools.md`, `security-model.md`.
- Define MVP node types: `Repository, Module, Package, Class, Interface, Method, Field, Constructor, Test, Endpoint`.
- Define MVP relationship types (narrowed from the full list): `CONTAINS, IMPORTS, EXTENDS, IMPLEMENTS, CALLS, TESTS, DEPENDS_ON`.
- **New:** Select 2–3 real open-source Spring Boot repositories of varying size/complexity as the permanent accuracy benchmark (pin exact commits in `benchmark-repos/`). These are used from Phase 2 onward — not deferred to a later "integration testing" phase.
- **New:** Confirm which of the benchmark repos use Lombok and/or Spring AOP proxies, since these are near-guaranteed in real Spring code and will break naive resolution if ignored.
- **New:** Create **failure-mode catalog** — document known unsolvable cases (reflection-heavy code, dynamic proxies beyond Spring AOP, SpEL expressions, runtime bytecode generation) as explicit non-goals so they're tracked limitations, not silent bugs.

**Exit criteria:** Docs reviewed; benchmark repos cloned and buildable locally; node/edge schema frozen for MVP.

---

## Phase 1 — Repository Discovery & Classpath Resolution

**Goal:** Understand a repository's structure *and* its real dependency classpath before any parsing happens. This phase absorbs what the original plan treated as a later "confidence" concern — without a resolved classpath, symbol resolution isn't uncertain, it's simply wrong.

- Build `RepositoryScanner`: discover files, languages, build system, modules, test directories, generated code, ignored paths.
- Respect `.gitignore` and a RepoMind-specific `.repomindignore`.
- **New — critical path:** Build `ClasspathResolver`:
  - Shell out to `mvn dependency:build-classpath` or Gradle's resolved configuration to get actual resolved jars (not guessed versions).
  - Cache resolved classpath per module; invalidate on `pom.xml`/`build.gradle` change.
  - Fail loudly (not silently) if classpath resolution fails for a module — a partially-resolved classpath produces misleading confidence scores downstream.
- Identify Maven/Gradle multi-module structure (`settings.gradle`, parent `pom.xml`) and record module boundaries in the graph.

**Exit criteria:** Given any benchmark repo, RepoMind can enumerate all source files and produce a fully resolved classpath (source + third-party jars + JDK) for every module.

---

## Phase 2 — Semantic Java Parsing (not just AST)

**Goal:** Parse source into a normalized model with real type resolution, validated against a real repo immediately — not after weeks of infrastructure work.

- Configure JavaParser with **JavaSymbolSolver**, wired to Phase 1's resolved classpath (source roots + jars + reflection-based JDK resolution).
- Build the normalized intermediate model (`CodeClass`, `CodeMethod`, `CodeField`, etc.) — JavaParser objects never leak outside the parser module.
- **New — validation gate:** Before proceeding to Phase 3, hand-pick 5–10 representative files from a benchmark repo (including at least one with Lombok annotations and one with an interface implemented via a Spring-managed bean) and manually verify that types resolve correctly. This is the single most important checkpoint in the whole plan — if semantic resolution is wrong here, everything built on top of it will silently be wrong too.
- **New:** Add baseline Lombok annotation handling (`@Data`, `@Builder`, `@Getter/@Setter`, `@Slf4j`, `@RequiredArgsConstructor`) so generated methods/fields aren't treated as missing. This was deferred to V0.2 in the original plan; given the target ecosystem (Spring Boot), it needs to be in MVP or the symbol table will be full of phantom gaps in most real repos.
- **New:** Run annotation processors during parsing (MapStruct, QueryDSL, Immutables, Lombok delombok). Configure JavaSymbolSolver with processor-generated sources via Maven `annotationProcessorPaths` or Gradle `annotationProcessor` classpath. Fail loudly if processor execution fails — generated code gaps are silent correctness killers.

**Exit criteria:** Semantic parsing runs cleanly across all three benchmark repos with zero unresolved-symbol errors on the hand-checked sample set.

---

## Phase 2.5 — Configuration & Resource Graph (Spring Boot Specific)

**Goal:** Extract Spring Boot's declarative wiring (config, datasources, entities, migrations) so "change this YAML → what breaks?" is a graph query, not a heuristic.

- Parse `application.yml/.properties`, `*.xml` (Spring XML config), `schema.sql`, `migration/*.sql` (Flyway/Liquibase).
- Model: `ConfigProperty`, `DataSource`, `Entity`, `Table`, `Column`, `RepositoryMethod`, `Migration`, `ConfigurationClass`.
- Edges: `BINDS_TO` (property → config class), `INJECTS` (datasource → repository), `MAPS_TO` (entity → table, column), `DEFINES` (repository method → derived query), `MIGRATES` (migration → table).
- Persist to SQLite alongside symbol index; queryable via same API.

**Exit criteria:** Given a benchmark repo, changing a datasource URL in `application.yml` correctly traces to all repositories/entities using it; adding a column to a migration traces to affected entities and repository queries.

---

## Phase 3 — Symbol Index

**Goal:** A queryable symbol table backed by the semantically-resolved model from Phase 2.

- Build fully-qualified-name → entity resolution for classes, interfaces, methods, fields, packages, files.
- Support `find class / interface / method / field / package / file` queries.
- Persist symbols to SQLite (`nodes` table: id, type, name, qualified_name, file_path, line_start, line_end).

**Exit criteria:** Every symbol in a benchmark repo is resolvable by fully-qualified name in under 50ms.

---

## Phase 4 — Dependency Graph

**Goal:** Structural relationships between types.

- Extract `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `USES` edges.
- Persist to SQLite `edges` table: source_id, target_id, type, confidence, metadata.
- Introduce the `confidence` field now, but treat it as provisional — it gets calibrated in Phase 5's eval harness, not hard-coded.

**Exit criteria:** Dependency graph for a benchmark repo matches manual spot-checks on 10+ classes with known inheritance/interface relationships.

---

## Phase 5 — Call Graph + Confidence Validation Harness

**Goal:** This is the technical core of the product, and the phase most likely to silently fail without a way to measure correctness.

- Build `CALLS` edges: static calls, instance calls, constructor calls, field-based calls.
- Handle interface dispatch and inheritance without pretending to solve dynamic dispatch perfectly — represent genuinely ambiguous cases explicitly:
  ```text
  Confirmed:  ReportController.generate() → ReportService.generateReport()
  Possible:   ScheduledReportJob.execute() → ReportService.generateReport()
  ```
- **New:** Add baseline handling for Spring-managed dispatch — `@Autowired`/constructor injection resolving an interface to its concrete Spring bean, and awareness that `@Transactional`/AOP-proxied methods are called through a proxy, not directly. Without this, a large fraction of Spring "service" call edges will be misclassified as low-confidence or missed entirely.
- **New:** Extract Spring Boot configuration model — `@ConfigurationProperties` binding (YAML → typed config classes), `@Value`/`@PropertySource` resolution, `application.yml`/`application.properties` → bean wiring. Model `ConfigProperty`, `DataSource`, `ConfigurationClass` nodes with `BINDS_TO`, `INJECTS` edges. Enables "change this config → what breaks?" queries.
- **New — `core/eval/` module:** Build a lightweight accuracy harness:
  - Take 15–20 real PRs from the benchmark repos.
  - Hand-label what files/methods were actually touched or logically affected.
  - Run `find_callers`/`analyze_change_impact` against the pre-PR commit and score precision/recall against the hand-labeled set.
  - **Mutation testing:** Introduce deliberate bugs in benchmark repos (delete a method call, change a return type, remove an annotation), verify impact analysis catches them — this tests the "what breaks" direction, not just "what was changed."
  - This becomes the ongoing metric for "is RepoMind actually right" — more important than latency targets, and it's how the confidence-score weights (e.g., is 0.72 the right number vs. 0.55?) get calibrated instead of invented.

**Exit criteria:** Call graph precision/recall against the hand-labeled benchmark PRs is measured and documented (not necessarily perfect — but known and tracked).

---

## Phase 6 — Test Graph

**Goal:** Map production code to the tests that exercise it.

- Direct references: test explicitly references the class/method → `TESTS` edge.
- Transitive: Test → Controller → Service, surfaced as "potentially affected" rather than confirmed.
- Validate against the same benchmark PRs used in Phase 5 — did RepoMind correctly flag the tests that the actual PR touched or that CI would have needed to run?

**Exit criteria:** Test graph recall on benchmark PRs is measured and documented.

---

## Phase 7 — In-Memory Graph Traversal Layer

**Goal:** Address a scaling risk the original plan deferred to Phase 19 — multi-hop traversal over SQLite alone (recursive CTEs / N+1 app-level BFS) will get slow well before 130K+ edges, especially on hub classes with many transitive dependents.

- SQLite remains the durable store; it is **not** the traversal engine.
- Build an in-process in-memory graph structure (adjacency lists keyed by node id) hydrated from SQLite at index-load time, used for all multi-hop queries (`find_callers` transitively, impact analysis, test graph traversal).
- **Memory budget:** Graph must fit in <2GB heap for 100K nodes / 500K edges. Define LRU eviction strategy on least-recently-traversed subgraphs *before* hitting OOM — not after.
- Benchmark traversal time on the largest benchmark repo's most-connected class ("hub class") explicitly — this is the worst case, not the average case, and should be tested now rather than discovered in a later performance phase.
- Do **not** adopt Neo4j — an in-memory layer over SQLite is sufficient at this scale and keeps the local-first, zero-infrastructure story intact.

**Exit criteria:** Transitive traversal on the benchmark repo's most-connected node completes in under 200ms.

---

## Phase 8 — Impact Analysis Engine & Deterministic Scoring

**Goal:** The product's headline feature — with scoring that's calibrated, not invented.

- Traversal: Target → Callers → Dependencies → Implementations → Tests → Endpoints.
- Deterministic scoring inputs (weights as a starting point, not final):
  ```text
  Public API                +30
  Many callers               +20
  Database interaction       +20
  High fan-out                +15
  Few tests                   +10
  Architecture violation      +10
  ```
  ```text
  0–20    LOW
  21–50   MEDIUM
  51–75   HIGH
  76–100  CRITICAL
  ```
- **New:** Extract JPA/Hibernate entity → table mapping (`@Entity`, `@Table`, `@Column`, `@JoinColumn`), `@Repository` query methods → derived SQL, Flyway/Liquibase migrations. Model `Entity`, `Table`, `Column`, `RepositoryMethod`, `Migration` nodes with `MAPS_TO`, `DEFINES`, `MIGRATES` edges. "Database interaction +20" becomes a graph fact, not a heuristic.
- **New:** Re-run the Phase 5 eval harness against full impact analysis output (not just call graph) on the benchmark PRs. Adjust weights based on where the score disagreed with what actually turned out to matter in those real PRs, and document the adjustment — the score should be traceable to evidence, not just intuition.
- **New:** Sensitivity analysis — vary each weight ±20% and measure ranking stability on benchmark PRs. Document which weights are decisive vs. noise. Unstable weights indicate missing graph signals, not tuning opportunities.
- Principle carried over from the original plan and worth restating: **the AI explains the score; it does not invent the score.**

**Exit criteria:** Impact score correlates with hand-labeled PR risk on the benchmark set, and the weight-tuning rationale is documented.

---

## Phase 9 — Query Engine, CLI, and MCP Server

**Goal:** Expose the engine through a terminal and to AI agents, with output that won't blow past context limits.

- Core query tools: `find_symbol, find_callers, find_callees, find_dependencies, find_implementations, find_related_tests, trace_execution, analyze_change_impact, explain_module, architecture_check`.
- CLI (Picocli): `repomind init`, `repomind index`, `repomind analyze <symbol>`, `repomind impact <symbol>`, `repomind diagnostics`.
- MCP server exposing the same tools with structured JSON output (never prose).
- **New — output size discipline:** A hub class in a 5,000-file repo can have hundreds of callers and thousands of indirect dependencies. Design every MCP tool response with:
  - A default result cap (e.g., top N by relevance/confidence).
  - An explicit `"truncated": true, "totalCount": N, "returnedCount": M"` field.
  - A follow-up pattern (`find_callers` with offset/pagination) rather than dumping the full graph into a single tool result.
  - **Streaming responses:** For huge result sets, yield pages incrementally via MCP streaming rather than truncating — the agent decides when to stop consuming.
  - This must be part of the tool contract from the start, not retrofitted after a context-window failure in testing.

**Exit criteria:** An AI agent connected via MCP can ask "what would be affected if I change `generateReport()`?" and get a correctly-scoped, non-truncating-by-accident structured response.

---

## Phase 10 — Incremental Indexing

**Goal:** Make RepoMind usable during active development, not just as a one-time batch job.

- Detect created/modified/deleted/renamed files via git diff.
- Reparse only affected files → update symbols → update edges → recalculate affected subgraph (using the Phase 7 in-memory layer, not a full re-hydration).
- **Cross-module invalidation:** Changing a shared DTO in `common` module must re-index all dependent modules. Track module-level dependency graph from Phase 1 to compute transitive invalidation set.
- Target: incremental update on a small change completes in under 1 second on benchmark repos.

**Exit criteria:** After a single-method edit on a benchmark repo, incremental re-index completes within budget and downstream query results remain correct (verified against a fresh full re-index as ground truth).

---

## Phase 11 — Architecture Rules (Lightweight)

**Goal:** Begin the "architecture intelligence" story without over-building it.

- Simple YAML rule config (`controllers-cannot-access-repositories`, `domain-cannot-depend-on-web`, etc.).
- Detect and report violations using existing dependency graph — no new infrastructure required.

**Exit criteria:** Rule violations are correctly detected on at least one benchmark repo with a deliberately introduced violation.

---

## Phase 12 — Security & Local-First Guarantees

**Goal:** Bake in the enterprise-relevant story now, since retrofitting security posture later is harder than designing for it from the start.

- Source code stays local by default — parsing, indexing, and storage never leave the developer's machine.
- If/when an AI provider is involved, only structured context (query results) crosses that boundary — never raw source or the full repository.
- No default telemetry. `repomind diagnostics` provides local-only visibility (indexed file/symbol/edge counts, timing, parser warnings).

**Exit criteria:** Documented data-flow diagram showing no network calls during indexing/querying, verified by running with network access disabled.

---

## Phase 13 — Testing Strategy (Fixtures + E2E)

**Goal:** Now that correctness has been validated continuously against real repos since Phase 2, formalize fixture-based regression testing to prevent drift.

- Synthetic fixtures for edge cases the benchmark repos may not exercise: circular dependencies, multi-module builds, deep inheritance, interface dispatch ambiguity.
- Each fixture has an explicit expected-graph assertion (`callers(C) = [B, A]`, etc.).
- End-to-end test: Repository → Index → Graph → Query → Impact → MCP → agent-facing response, run as a single chained test.
- Keep the Phase 5/8 eval harness running in CI against the benchmark repos as a standing regression check — this is distinct from fixtures and should never be dropped once established.

**Exit criteria:** CI runs fixtures + benchmark-repo eval harness on every merge; regressions in precision/recall are caught automatically.

---

## Phase 14 (Stretch) — VS Code Extension & Visualization

**Goal:** UI layer, explicitly de-scoped to "cut if behind schedule" — the CLI + MCP path is the non-negotiable core that proves the product thesis to an AI agent consumer, not the editor integration.

- Command palette: Index Repository, Analyze Symbol, Analyze Change Impact, Show Callers, Show Dependencies.
- Context-menu "Analyze Change Impact" with a simple results panel.
- Basic interactive graph visualization (click node → expand callers/dependencies) — nice-to-have for demo polish, not required for MVP validation.

**Exit criteria (if pursued):** Extension calls into Core only through the same API surface as CLI/MCP — no logic duplicated in the extension itself.

---

## Definition of Done

MVP is not complete until this full workflow succeeds on a **real, previously-unseen Spring Boot repository** (not just the pinned benchmarks):

```text
1. Clone RepoMind
2. Point it at a real Spring Boot repository
3. repomind index → classpath resolved, graph built
4. repomind impact ReportService.generateReport()
   → correct callers, dependencies, tests, APIs, architecture warnings
   → risk score with documented, evidence-based weighting
5. Start MCP server, connect an AI coding agent
6. Ask: "What would be affected if I change generateReport()?"
   → agent gets structured, appropriately-truncated data, not raw prose
7. Modify a file → incremental update completes in <1s
8. Repeat analysis → results remain correct
9. Precision/recall against the eval harness is known and documented,
   not assumed
10. **Eval harness passes on this repo without weight retuning** — if scores need per-repo adjustment, the scoring isn't generalizable
11. **MCP agent completes a realistic refactoring task** (e.g., "rename `generateReport` to `createReport` across the codebase") using only RepoMind tools
```

---

## Revised Timeline

The original 12-week estimate is optimistic once classpath resolution, Lombok/Spring-proxy handling, and a real eval harness are treated as MVP requirements rather than deferred. A more realistic solo-alongside-a-job timeline:

| Weeks | Focus |
|---|---|
| 0 | **Pre-spike (1 week):** Validate parser stack end-to-end on one benchmark repo — JavaParser + JavaSymbolSolver + custom classpath + annotation processors. If this fails, replan before investing. |
| 1–2 | Phase 0–1: Spec, benchmark repo selection, classpath resolver |
| 3–5 | Phase 2: Semantic parsing + Lombok/annotation processor handling, validated continuously against benchmark repos |
| 6 | Phase 2.5: Configuration & resource graph (Spring Boot specifics) |
| 7 | Phase 3–4: Symbol index, dependency graph |
| 8–10 | Phase 5: Call graph + Spring-aware dispatch + eval harness build-out + mutation testing |
| 11 | Phase 6: Test graph |
| 12 | Phase 7: In-memory traversal layer + hub-class benchmarking + memory budget validation |
| 13–14 | Phase 8: Impact engine + score calibration + sensitivity analysis |
| 15–16 | Phase 9: Query engine, CLI, MCP server with output-size discipline + streaming |
| 17 | Phase 10: Incremental indexing + cross-module invalidation |
| 18 | Phase 11–12: Architecture rules, security posture |
| 19–20 | Phase 13: Fixtures, E2E, CI eval harness wiring |
| 21+ | Phase 14 (stretch): VS Code extension, visualization — only if on schedule |

**~21 weeks** for a genuinely validated MVP, versus the original 12-week estimate. The extra weeks are front-loaded into the parts most likely to silently produce wrong answers (semantic resolution, Spring/Lombok/annotation processors, config graph, and the eval harness) rather than backend into polish.

---

## Product Framing

Lead with the **confidence distinction**, not "AI":

> RepoMind doesn't pretend static analysis is perfect. It tells you what it's *confirmed* and what's *possible* — and it proves that distinction against real PRs from real repositories, not synthetic fixtures.

That's a sharper, more defensible pitch than "codebase intelligence" alone, and it's backed by the eval harness this plan builds in from Phase 5 onward rather than bolting on at the end.

**Differentiators that survive the hype cycle:**

1. **Confirmed vs. Possible** callers — the only product that makes this distinction explicit and validated
2. **Classpath-aware** — not AST-only; resolves actual Spring beans, not just interface declarations
3. **Config-aware** — `application.yml` → runtime wiring is a graph edge, not a grep result
4. **Calibrated, not invented** scores — weights traced to mutation-tested PRs, documented sensitivity
5. **Local-first by architecture** — core never depends on UI, cloud, or telemetry; dependency direction is provable

---

(End of file)