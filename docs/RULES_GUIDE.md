# RepoMind Architecture Rules Guide

RepoMind allows teams to define and enforce clean architecture boundaries, layered architectures, and domain isolation via `.repomind/rules.yaml`.

## Configuration File Location

Place your rules in `.repomind/rules.yaml` at the root of your repository. RepoMind automatically loads and validates this file during CLI runs, CI/CD checks, and MCP server queries.

## Rule Schema

```yaml
rules:
  - name: unique-rule-identifier
    description: Human-readable purpose of this constraint
    from:
      namePattern: "regex-pattern-matching-caller-package-or-class"
      annotations: ["Annotation1", "Annotation2"]
    to:
      namePattern: "regex-pattern-matching-callee-package-or-class"
      annotations: ["TargetAnnotation"]
    message: Helpful guidance shown when this rule is violated
    edgeKinds: [CALLS, USES, EXTENDS, IMPLEMENTS]
```

### Supported Edge Kinds
- `CALLS`: Method call or constructor invocation
- `USES`: Field usage, method parameter/return reference, mapper dependency
- `EXTENDS`: Class inheritance
- `IMPLEMENTS`: Interface implementation
- `IMPORTS`: Package import statements

## Common Patterns

### 1. Layered Architecture (Controllers cannot access Repositories)
```yaml
rules:
  - name: controllers-cannot-access-repositories
    description: Presentation layer must access persistence via service layer
    from:
      annotations: [RestController, Controller]
    to:
      annotations: [Repository]
    message: Use an intermediate @Service rather than calling repositories directly
    edgeKinds: [CALLS, USES]
```

### 2. Domain Model Isolation (Domain cannot depend on Infrastructure)
```yaml
rules:
  - name: domain-must-not-depend-on-infra
    description: Core domain business models must remain free of DB/framework coupling
    from:
      namePattern: ".*\\.domain\\..*"
    to:
      namePattern: ".*\\.infra\\..*"
    message: Invert dependency using interfaces defined in the domain layer
    edgeKinds: [CALLS, USES, EXTENDS, IMPLEMENTS]
```

### 3. Core Engine Isolation (Core cannot depend on Apps)
```yaml
rules:
  - name: core-must-not-depend-on-apps
    description: Core libraries must never take dependencies on application frontends
    from:
      namePattern: "dev\\.repomind\\.core\\..*"
    to:
      namePattern: "dev\\.repomind\\.(cli|mcp).*"
    message: Core components cannot reference application-layer code
    edgeKinds: [CALLS, USES, EXTENDS, IMPLEMENTS]
```

## Enforcing in CI/CD

To fail a build if any architectural violations exist, use the `--fail-on-violation` flag:
```bash
./gradlew run --args="rules check --repo . --fail-on-violation"
```
Offending lines will be reported with exact file paths, line numbers, and rule messages.
