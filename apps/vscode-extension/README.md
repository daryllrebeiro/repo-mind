# RepoMind VS Code Extension

RepoMind extension brings deterministic codebase intelligence, blast radius impact analysis, and real-time architectural boundary enforcement directly to Visual Studio Code.

## Features

- **Index Workspace**: Automatically triggers RepoMind semantic indexing and symbol database generation.
- **Analyze Impact Under Cursor**: Right-click any method, class, or interface to compute its transitive blast radius, certain/possible callers, and affected test suites.
- **Architecture Rules Governance**: Automatically runs `.repomind/rules.yaml` rules against your project and flags violations as native editor Diagnostics on the exact offending line.

## Getting Started

1. Ensure the `repomind` CLI binary is on your `PATH` (or configure `repomind.cliPath` in Settings).
2. Open any Java / Kotlin workspace with a Gradle or Maven build file.
3. Open Command Palette (`Ctrl+Shift+P` / `Cmd+Shift+P`) and run `RepoMind: Index Workspace`.
