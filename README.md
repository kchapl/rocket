# rocket

A single-user Command Line Interface (CLI) tool for setting long-term career ambitions, committing quarterly objectives, and executing implementation outcomes with upward traceability.

- **[CONTEXT.md](CONTEXT.md)**: The domain language and three-tier hierarchy rules. Read before naming anything.
- **[DESIGN.md](DESIGN.md)**: Architecture specification, CLI command surface (Planning vs. Implementation modes), SQLite schema, and implementation plan.

## Toolchain

Configured via `mise.toml`:
- Scala 3 (`scala-cli` 1.18.0)
- Java 27 (OpenJDK)
