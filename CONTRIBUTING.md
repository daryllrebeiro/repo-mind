# Contributing to RepoMind

Thank you for contributing to RepoMind! This document provides instructions for setting up your development environment, building the project, and submitting contributions.

## Prerequisites

- **Java Development Kit (JDK)**: JDK 21 LTS (Temurin recommended)
- **Gradle**: 9.x (use the included `./gradlew` wrapper)
- **Node.js**: 20+ (for developing `apps/vscode-extension`)

## Project Structure

- `core/model`: Domain models, limits, hash utilities, security bounds (`PathGuard`)
- `core/scanner`: Multi-module build discovery (Maven, Gradle Kotlin/Groovy)
- `core/classpath`: Classpath resolution and caching
- `core/config`: Spring configuration and bean graph extractor
- `core/graph`: Adjacency list in-memory dependency graph
- `core/impact`: Deterministic risk score and blast radius analyzer
- `core/rules`: Architectural boundary and layering rules engine
- `core/query`: Query engine for symbols, callers, callees, tests, and graph
- `core/index`: Incremental indexing engine with cross-module invalidation
- `core/eval`: Precision and recall benchmark evaluation harness
- `language/java`: JavaParser semantic parser with Spring & MapStruct dispatch
- `storage/sqlite`: SQLite persistence with WAL mode
- `apps/cli`: RepoMind command line interface
- `apps/mcp-server`: Model Context Protocol (MCP) stdio server
- `apps/vscode-extension`: Visual Studio Code extension

## Building & Testing

### Build the entire project
```bash
./gradlew build
```

### Run all tests
```bash
./gradlew test
```

### Run static analysis and linting
```bash
./gradlew detekt ktlintCheck
```

### Format code with ktlint
```bash
./gradlew ktlintFormat
```

## Pull Request Guidelines

1. Ensure all 86+ Gradle tasks pass (`./gradlew check`).
2. Adhere to Kotlin coding conventions and avoid wildcard imports.
3. Keep stderr separated from stdout so JSON outputs for CLI/MCP remain machine-parseable.
4. Add tests for any new parser dispatch or analysis capabilities.
