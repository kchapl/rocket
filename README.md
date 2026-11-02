# rocket

A single-user Command Line Interface (CLI) tool for setting long-term career ambitions, committing quarterly objectives, and executing outcomes with upward traceability.

- **[CONTEXT.md](CONTEXT.md)**: The domain language and three-tier hierarchy rules. Read before naming anything.
- **[DESIGN.md](DESIGN.md)**: Architecture specification, CLI command surface (Planning vs. Throughput modes), SQLite schema, and implementation plan.

## Toolchain

Configured via `mise.toml`:
- Scala 3.9.0, built with `scala-cli` 1.18.0
- Java 27 (OpenJDK)

Preparation, once per machine:

```sh
mise install
```

That installs the pinned Java and `scala-cli`. Any `scala-cli` 1.18+ on `PATH` with a compatible JDK works too. The first build downloads Scala and the SQLite driver, so it needs network access once; later builds are cached.

## Running it

Arguments after `--` are passed through to `rocket`, so from the repository root:

```sh
scala-cli run . -- career add "become a staff engineer" --why "to choose my own work"
scala-cli run . -- career list
```

To get something callable without the `scala-cli` wrapper, build a jar. The `--power` flag is required because `scala-cli` restricts `-o`:

```sh
scala-cli package . --power -o .scala-build/rocket.jar --assembly --force
./.scala-build/rocket.jar career add "become a staff engineer"
./.scala-build/rocket.jar career list
```

Native-image packaging (DESIGN.md §7, Phase 5) is not set up yet.

## The store

No preparation: the SQLite file is created on first run at `~/.local/share/rocket/rocket.db`, or under `$XDG_DATA_HOME/rocket/` when that variable is set. Point `ROCKET_DB` elsewhere to keep an experiment out of your real record:

```sh
ROCKET_DB=/tmp/scratch.db scala-cli run . -- career list
```

## What works today

Both modes: the hierarchy is shaped in Planning Mode and worked in Throughput Mode.

| Command | Purpose |
|---|---|
| `rocket career add <title> [--why <text>] [--verification <text>]...` | Create a Career Goal |
| `rocket career list [--archived]` | List active Goals, or archived ones |
| `rocket career archive <id>` / `unarchive <id>` | Archive a Goal, or return it to pursuit |
| `rocket career rename <id> <title>` | Rename a Goal, keeping its id |
| `rocket quarter [current \| list]` | Show the current Quarter, or list the Quarters in the record |
| `rocket quarter review [quarter]` | Report the Quarter's Gaps: Unaligned Goals, Empty Objectives, Parentless Outcomes |
| `rocket objective add <title> --goal <id> [--why <text>] [--verification <text>]...` | Commit a Quarterly Objective to the current Quarter |
| `rocket objective drop <id>` | Drop an Objective by dropping every `open` Outcome under it |
| `rocket objective rename <id> <title>` | Rename a Quarterly Objective, keeping its Goal and Quarter |
| `rocket outcome add --objective <id> <title> [--why <text>] [--verification <text>]...` | Plan an Outcome under an Objective |
| `rocket outcome add <title> [--why <text>] [--verification <text>]...` | Plan an unfiled Outcome — parentless and unflagged |
| `rocket outcome add --emergency <title>` | Add an unparented emergency Outcome |
| `rocket outcome parent <outcome-id> <objective-id>` | File an Outcome under an Objective, clearing the emergency flag |
| `rocket outcome rename <id> <title>` | Rename an Outcome, keeping its Status, Stage and place in the queue |
| `rocket inbox add <text>` | Capture an Idea |
| `rocket inbox list [--all]` | List `pending` Ideas; `--all` also shows promoted and discarded ones |
| `rocket inbox promote <id> [--to-goal \| --to-obj <goal-id> \| --to-out]` | Turn an Idea into a Goal, Objective or parentless Outcome; the Idea is kept, recording what it became |
| `rocket inbox discard <id>` | Mark an Idea `discarded`; it stays as the record of the decision |
| `rocket backlog [list] [--all]` | The Quarter's ranked queue of `open` Outcomes; `--all` adds achieved and dropped |
| `rocket backlog reorder <id> <+N \| -N>` | Move an Outcome within the Quarter's queue; terminal Outcomes keep their slot |
| `rocket progress start <id>` | Move an Outcome's stage from `todo` to `doing` |
| `rocket progress box <id>` | Record a completed Time Box against the current Quarter, and start the work |
| `rocket progress achieve <id>` / `drop <id>` | Close an Outcome; a closed Outcome takes no further Time Box |

Utility:

| Command | Purpose |
|---|---|
| `rocket trace <outcome-id>` | Show the whole upward chain: Outcome, its Objective, that Objective's Goal |

Every command first carries the record into the current Quarter if a calendar boundary has passed: open Outcomes move forward with their Time Boxes left in the Quarter they were recorded in, each parent Objective with live work is re-committed into the new Quarter, and one notice line reports it. Nothing is carried into the Quarters that were skipped.

```text
Notice: 2026-Q1–Q3 closed. Carried forward 4 open outcomes into 2026-Q4.
```

Repeatable `--verification` flags keep the order given. Ids accept an exact id or a unique case-insensitive prefix of the id or title. Exit codes: `0` success, `1` domain error (unknown or ambiguous id, archived Goal, cross-Quarter parent, already-closed Outcome), `2` usage error.

Not yet implemented: `export` and `import`.

Tests: `scala-cli test .`
