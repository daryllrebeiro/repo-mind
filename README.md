# RepoMind

**Codebase intelligence for AI agents.**

Given a code change, RepoMind determines what it can affect and explains why — using an actual repository graph rather than LLM guesswork.

> RepoMind doesn't pretend static analysis is perfect. It tells you what it's *confirmed* and what's *possible* — and it proves that distinction against real PRs from real repositories, not synthetic fixtures.

## What it does today

- **`repomind scan <repo>`** — scans a repository and reports its structure as structured JSON: build system detection (Maven, Gradle/Groovy, Gradle/KTS), multi-module discovery via `pom.xml <modules>` and `settings.gradle` includes, source roots (`src/main|test/{java,kotlin}`), and per-module source file counts — respecting `.gitignore`.
- **`repomind classpath <repo>`** — resolves the *real* dependency classpath for every module by shelling out to the project's own build tool (`mvn dependency:build-classpath`, or a Gradle init-script task resolving `runtimeClasspath`/`compileClasspath`). Results are cached under `.repomind/cache/classpath/` keyed by module path and invalidated by content-hash of the build file. Resolution failures are loud, never silent: a partially-resolved classpath produces misleading confidence downstream.

## Why classpaths matter

Symbol resolution requires a real classpath, not just an AST. Without resolved third-party jars, every type from an external library is a phantom gap and call edges are simply wrong — not "uncertain," wrong. That's why classpath resolution is Phase 1 of this plan, before any parsing happens.

## Stack

| Component | Technology |
|---|---|
| Core | Kotlin (latest stable) on Temurin JDK 25 LTS |
| Build | Gradle 9.x (Kotlin DSL), version catalog |
| Parsing (upcoming) | JavaParser + JavaSymbolSolver wired to resolved classpaths |
| Persistence (upcoming) | SQLite |
| CLI | Picocli |
| Agent interface (upcoming) | MCP server over stdio JSON-RPC |

The JVM is used deliberately: true semantic Java resolution exists only in its ecosystem (JavaParser/JDT). The extractor is an interface, not the product — query, traversal, impact, and MCP layers stay language-agnostic over a normalized model.

## Building

```bash
./gradlew build        # compile + run all tests
./gradlew :cli:installDist
apps/cli/build/install/cli/bin/cli.bat scan path/to/repo
apps/cli/build/install/cli/bin/cli.bat classpath path/to/repo
```

Requires JDK 25 (`JAVA_HOME`). Maven/Gradle of the analyzed repo must be on PATH for classpath resolution.

## Architecture

```
apps/cli            ← UI layer
core/scanner        ← repository & module discovery
core/classpath      ← build-tool classpath resolution + cache
core/model          ← normalized model shared by everything
language/java       ← Java-specific extraction (Phase 2)
storage/sqlite      ← durable store (Phase 3)
```

Hard rule: `core/` never imports anything from `apps/`. Dependency direction is always UI → API → Core.

## Roadmap

See [FEATURES.md](FEATURES.md) for shipped features, what's next, and future plans. The full phase-by-phase implementation plan is in [InitialPlan.md](InitialPlan.md).

## Status

Pre-alpha. Phases 0–1 of the plan are implemented; semantic parsing (Phase 2) is next.
