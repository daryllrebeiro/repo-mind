# Security Policy

## Scope

RepoMind is a local developer tool that analyzes source code repositories and exposes results through a CLI and an MCP server bound to stdio. There is no network service, no telemetry, and no account system.

## Reporting

Open a GitHub issue marked `security` for anything you believe is exploitable. Include reproduction steps against a public fixture repository.

## Guarantees

1. **No telemetry, ever.** RepoMind makes no outbound connections of its own. There is no analytics, crash reporting, or update checking.
2. **Network-isolated by default.** Build-tool invocations (`mvn`, `gradle`) run with `--offline`. Outbound dependency downloads require the explicit `--online` flag on `repomind classpath`.
3. **Local data only.** All state lives under `<repo>/.repomind/` (index database, classpath cache, generated reports). Nothing leaves the machine except when you choose to commit or copy those files.
4. **Untrusted-input hardening.** Analyzed repositories are treated as untrusted input (see docs/security-model.md): process arguments are validated and cmd-escaped on Windows, YAML is parsed with SnakeYAML's SafeConstructor only, filesystem access is guarded against traversal escapes, and untrusted identifiers are neutralized before entering markdown reports.

## Known limitations

- Running `repomind classpath --online` executes the analyzed project's own build tooling with network access. Only use it on repositories whose build files you have reviewed — this is inherent to resolving real classpaths.
- Vulnerability/CVE scanning is out of scope; report findings are structural only.

Supported version: latest `main`.
