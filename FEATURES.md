# RepoMind — Features

What RepoMind does, what it's going to do, and what's planned beyond the MVP.

---

## Shipped

### Repository scanning (`repomind scan`)
- Build system detection: Maven, Gradle (Groovy & Kotlin DSL), unknown fallback
- Multi-module discovery: Maven `<modules>` (recursive) and Gradle `settings.gradle(.kts)` includes
- Source root discovery per module: `src/main/{java,kotlin}` and `src/test/{java,kotlin}`, marked production vs test
- Source file counting with `.gitignore` support (negation, directory-only, anchored, `*`/`**` patterns)
- Structured JSON output — never prose

### Classpath resolution (`repomind classpath`)
- Real resolved classpaths via the project's own build tool, not guessed versions:
  - Maven: `dependency:build-classpath`
  - Gradle: injected init-script task resolving `runtimeClasspath` + `compileClasspath`
- Per-module cache under `.repomind/cache/classpath/`
- Content-hash invalidation on build-file change (touch-without-change stays cached)
- **Loud failure** on unresolved classpaths — a partial classpath would silently corrupt every confidence score downstream, so it refuses to proceed instead
- Per-module JSON results with cache-hit flag and entry counts

### Infrastructure
- Gradle 9 multi-module build with enforced dependency direction: UI → API → Core
- JDK 25 LTS toolchain, version catalog pinning all dependencies
- Test suites for scanner and resolver logic (fake command runner — no Maven/Gradle needed in CI)

---

## In progress / Next

### Phase 2 — Semantic Java parsing
- JavaParser + JavaSymbolSolver wired to Phase 1's resolved classpaths (source roots + jars + JDK reflection)
- Normalized intermediate model (`CodeClass`, `CodeMethod`, `CodeField`) — parser types never leak outside the language module
- Baseline Lombok handling (`@Data`, `@Builder`, `@Getter/@Setter`, `@Slf4j`, `@RequiredArgsConstructor`) so generated members aren't phantom gaps
- Annotation-processor output (MapStruct, QueryDSL, Immutables) included in resolution
- Validation gate: hand-checked files from a real benchmark repo before anything builds on top

### Phase 2.5 — Configuration & resource graph
- Parse `application.yml/.properties`, Spring XML, Flyway/Liquibase migrations
- Model config properties → bean wiring as graph edges ("change this YAML → what breaks?")

### Phase 3–4 — Symbol index & dependency graph
- FQN → entity symbol table persisted to SQLite (<50ms lookups)
- `IMPORTS`, `EXTENDS`, `IMPLEMENTS`, `USES` edges with provisional confidence values

### Phase 5 — Call graph + eval harness *(the core)*
- `CALLS` edges with explicit **Confirmed vs Possible** classification for interface dispatch
- Spring-aware dispatch: `@Autowired`/constructor injection resolving interfaces to concrete beans; AOP-proxy awareness
- Accuracy harness: precision/recall against hand-labeled real PRs from pinned benchmark repos + mutation testing
- Confidence weights calibrated against evidence, never invented

### Phase 6–8 — Tests, traversal, impact scoring
- Test graph mapping production code to exercising tests
- In-memory adjacency layer over SQLite (<200ms hub-class traversal, <2GB budget at 100K nodes)
- Impact analysis engine with deterministic, evidence-calibrated risk scores and sensitivity analysis
- JPA/Hibernate entity→table mapping so "database interaction" is a graph fact

### Phase 9–13 — Agents, incremental indexing, guardrails
- MCP server exposing query tools with strict output-size discipline (caps, pagination, truncation flags, streaming)
- CLI + MCP parity over one API surface
- Incremental indexing (<1s for small edits) with cross-module invalidation
- YAML architecture rules (e.g. controllers-cannot-access-repositories)
- Local-first security posture: no network during indexing/querying, no default telemetry
- Fixture regression tests + benchmark eval harness running in CI

---

## Beyond MVP (stretch)

- VS Code extension (command palette, impact panel, basic graph view) — cut first if behind schedule
- Escalation-path extractor behind the same interface (Eclipse JDT / scip-java) if JavaSymbolSolver precision proves insufficient on benchmarks
- Additional languages via the same normalized model
- Architecture-drift dashboards built on the rule engine

## Explicit non-goals (MVP)

Embeddings/vector DB/RAG · autonomous agents · cloud/SaaS/multi-user auth · GitHub/Jira/Slack integrations · distributed indexing · Neo4j or any dedicated graph DB · LLM-generated repo summaries.

The MVP question: **can we build a reliable, semantically-correct structural representation of a real Java/Spring codebase — including its classpath, not just its syntax?**
