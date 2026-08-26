# Security Model

RepoMind's core job is to run static analysis over repositories that may be **fully untrusted** (a cloned stranger's GitHub project). This document enumerates trust boundaries, threats, and the concrete controls in the codebase.

## Trust boundaries

| Input | Trust level | Enters via |
|---|---|---|
| Repository contents (sources, build files, YAML, resources) | Untrusted | scanner, parser, config extractor, classpath resolver |
| CLI arguments (paths, symbols) | Semi-trusted (local user) | every command |
| MCP client messages | Semi-trusted (local agent) | stdio JSON-RPC |

## Threats and controls

### T1 — Build-tool execution with network access
**Threat:** `mvn`/`gradle` execute build scripts from the analyzed repo and download dependencies. Running them online on a malicious repo means executing attacker-controlled code with network access.
**Control:** Classpath resolution runs `--offline` by default (`ClasspathResolver(allowNetwork = false)`). Network use requires an explicit user action: `repomind classpath --online`. Offline vs online results are cached under separate keys so a stale online result never masquerades as offline.
**Residual risk (documented, not solvable):** even offline, Maven/Gradle *execute* the repo's build scripts. Only analyze repos whose build files you have reviewed; this is inherent to real classpath resolution.

### T2 — Command injection through `cmd /c`
**Threat:** On Windows, `.cmd`/`.bat` launchers (mvn.cmd, gradle.bat) go through cmd.exe, which interprets metacharacters. A repo path containing `&`, `^`, `%` etc. could execute extra commands.
**Control:** Every argument passes `SafeArgs.validate` (rejects control characters outright) and `SafeArgs.escapeForWindowsCmd` (quotes anything outside a conservative safe pattern). Covered by unit tests.

### T3 — Path traversal / symlink escapes
**Threat:** User- or config-supplied relative paths escaping the repository root, or symlinks pointing outward.
**Control:** `PathGuard.requireDirectory` canonicalizes every command entry point; `resolveUnder` rejects absolute paths and normalized escapes, and verifies symlink targets stay inside the root.

### T4 — Deserialization attacks via repository YAML/config
**Threat:** Analyzed `*.yml` triggering arbitrary object construction.
**Control:** SnakeYAML used exclusively with `SafeConstructor(LoaderOptions())`; no polymorphic typing anywhere. Rule files and application configs are read as plain maps only.

### T5 — Markdown/report injection
**Threat:** Untrusted identifiers (class names, file paths) breaking out of code spans/headings/table cells in generated reports to inject rendered content or links.
**Control:** `MarkdownSanitizer` strips control characters and neutralizes backticks/pipes/angle brackets/caps length for all report-facing strings (repo name, findings, evidence). Mermaid labels are additionally escaped by `MermaidEmitter.sanitize`.

### T6 — Telemetry / exfiltration
**Threat:** Tool silently shipping data off-machine.
**Control:** There is no telemetry code path. The only outbound network capability in the entire codebase is the explicit `--online` flag above. This invariant is enforced by review; there is nothing to disable.

## Out of scope

- CVE/SAST scanning of analyzed repos (structural markers only)
- Multi-user hardening (single local user assumption)
- Sandboxing of the JVM itself (JVM-level escape is out of threat model)

## Verification

Security-relevant tests live next to their controls:
- `core/classpath/src/test/.../SecurityTest.kt` — offline-by-default, arg validation/escaping
- `core/model/src/test/.../PathGuardTest.kt` — traversal/symlink guards
- `core/report/src/test/.../MarkdownSanitizerTest.kt` — report injection neutralization
