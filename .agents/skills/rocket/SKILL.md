---
name: rocket
description: Read and write the local `rocket` career record through its CLI — the Backlog, Time Boxes, Status and Stage, Quarter Gaps, Ideas, and the Career Goal / Quarterly Objective / Outcome hierarchy. Use when asked what to work on next, to record a Time Box or mark an Outcome achieved or dropped, to review a Quarter, to capture or promote an Idea, or to add, rename, parent, archive or list the tiers.
---

# rocket

A local CLI over one SQLite record. Commands are not modes: nothing is "current" between runs, so each command stands alone and means the same thing on the hundredth run.

Read commands print plain text, and each also takes `--json` for one compact line when the answer needs slicing rather than reading: `career list` and `inbox list` an array, `backlog` an array of Outcomes, `quarter` a string, `quarter list` an array of names, `quarter review` `{quarter, unalignedGoals, emptyObjectives, parentlessOutcomes}`, `trace` `{goal, objective, outcome}`. Creates and state changes always answer in text: take the id off the line and pass it back in.

## Invoke

From the rocket project root, call the native binary: no JVM, a few milliseconds.

```sh
./.scala-build/rocket backlog
```

If it is missing, build it once (about a minute, needs a C compiler) and keep using the result:

```sh
scala-cli package . --power --native-image -o .scala-build/rocket --force -- \
  --no-fallback --initialize-at-build-time=scala.Enumeration
```

The record is `~/.local/share/rocket/rocket.db` (`$XDG_DATA_HOME/rocket/` when set); `ROCKET_DB` points at another file. Exit codes: `0` success, `1` domain error, `2` usage error.

## Reading stdout

| You ran                              | You get back                                                       |
| ------------------------------------ | ------------------------------------------------------------------ |
| a create or a promote                | the new id alone: `out3`                                           |
| a state change                       | the id and its new value: `out3 doing`, `obj2 dropped 1 outcome`    |
| a list                               | one line per record; `backlog` leads with rank, every other list with the id |
| nothing to list                      | a sentence, exit `0`: `no open Outcomes in 2026-Q4`                 |
| a domain error                       | empty stdout; the reason on stderr, exit `1`                        |

**The `Notice:` line.** Before the first command of a new calendar Quarter, one line is printed ahead of the command's own output:

```text
Notice: 2026-Q1–Q3 closed. Carried forward 4 open outcomes into 2026-Q4.
```

When a command answers with a bare id, take the last line. Under `--json` the notice moves to stderr, leaving stdout as one JSON value.

Ids are `g#`, `obj#`, `out#`, `idea#`. Every id argument also accepts an exact id or a unique case-insensitive prefix of an id or a title, which is convenient and quietly lossy: similarly named records fail with `out1 matches out1, out4`. Use the exact id, and keep it when a create or promote returns it — the Backlog reads only the current Quarter, and no command lists an Objective that is neither empty nor part of `quarter review`, so a lost id is hard to recover.

## Planning Mode: Goals, Objectives, Outcomes, Ideas

| Command                                                                    | stdout                                                        |
| -------------------------------------------------------------------------- | ------------------------------------------------------------- |
| `career add "<title>" [--why "<text>"] [--verification "<text>"]...`        | `g1`                                                          |
| `career list [--archived]`                                                  | `g1  <title>`, then `      why: …` and `      verification: a; b`; empty: `no Career Goals` |
| `career archive <id>` / `career unarchive <id>`                             | `archived g1` / `unarchived g1`                               |
| `career rename <id> "<title>"`                                              | `g1 renamed`                                                  |
| `quarter [current]`                                                         | `2026-Q4`                                                     |
| `quarter list`                                                              | one `YYYY-Qn` per line; empty: `no Quarters in the record`    |
| `quarter review [quarter]`                                                  | the Quarter, then `  Unaligned Goals`, `  Empty Objectives`, `  Parentless Outcomes` sections; clean: `2026-Q4: no Gaps` |
| `objective add "<title>" --goal <id> [--why "<text>"] [--verification "<text>"]...` | `obj1`                                                |
| `objective drop <id>`                                                       | `obj1 dropped 2 outcomes` — drops every `open` Outcome under it |
| `objective rename <id> "<title>"`                                           | `obj1 renamed`                                                |
| `outcome add --objective <id> "<title>" [--why] [--verification]...`        | `out1`                                                        |
| `outcome add "<title>" [--why] [--verification]...`                         | `out1`, parentless and unflagged — a Gap until filed           |
| `outcome add --emergency "<title>"`                                         | `out1`, parentless and flagged; refuses `--why`/`--verification` |
| `outcome parent <outcome-id> <objective-id>`                                | `out1 parented to obj1`                                       |
| `outcome rename <id> "<title>"`                                             | `out1 renamed`                                                |
| `inbox add "<text>"`                                                        | `idea1`                                                       |
| `inbox list [--all]`                                                        | `idea1  <text>`, plus `  (discarded)` or `  (promoted to g1)`; empty: `no pending Ideas` |
| `inbox promote <id> --to-goal \| --to-obj <goal-id> \| --to-out`             | the new `g1` / `obj1` / `out1`                                 |
| `inbox discard <id>`                                                        | `idea1 discarded`                                             |

## Throughput Mode: the Backlog

| Command                                    | stdout                                                                   |
| ------------------------------------------ | ------------------------------------------------------------------------ |
| `backlog [list] [--all]`                    | `1  out1  <title>` plus `  (doing)`, `  (emergency)`, `  (achieved)` or `  (dropped)`; `--all` adds terminal Outcomes; empty: `no open Outcomes in 2026-Q4` |
| `backlog reorder <id> <+N \| -N>`           | `out1 3` — the id and its new rank                                       |
| `progress start <id>`                       | `out1 doing`                                                             |
| `progress box <id>`                         | `out1 3 boxes` — the Outcome's total box count; also sets Stage `doing`  |
| `progress achieve <id>` / `progress drop <id>` | `out1 achieved` / `out1 dropped`                                      |
| `trace <outcome-id>`                        | Goal, Objective indented two, Outcome indented four, with `  (parentless)` when it has no Objective |

`export [--output <file.json>]` writes the whole record as one JSON document (`format: rocket`, ids, Time Boxes and `carriedThrough` included), and `import <file.json> [--force]` replaces the record with one, ids preserved; a store that already holds data refuses without `--force`.

There is no `objective list` and no Outcome list beyond the Backlog. `trace` is the read for one Outcome's whole lineage.

## The rules the record follows

- **A title states its Claim.** Write what is true once the work is done, not a name for the work: *"the migration runs on the new scheduler"*, not *"ship the migration"*. Titles that need "and" are two Commitments, or one Commitment and one Verification.
- **Three tiers, one unit of work.** A Career Goal is unbounded and archived rather than completed; a Quarterly Objective is committed to one Quarter and composed of Outcomes; an Outcome is the only thing worked. An Objective has no Status and no progress of its own — whether it is live, met, abandoned or empty is read off its Outcomes.
- **Status is achievement, Stage is work in flight.** `status` is `open`, `achieved` or `dropped`, and is the only answer to "is this done?"; `stage` is `todo` or `doing` and has no terminal value. `achieved` and `dropped` are final: they freeze the Stage and refuse further Time Boxes, and a repeat answers `out1 is already achieved`.
- **Every Time Box is one completed 25-minute unit**, recorded in the Quarter it happened, so an Outcome's effort is a count of boxes and not of days.
- **An Objective and its Outcome share a Quarter.** Filing across a boundary is refused — `Quarterly Objective obj1 is committed in 2026-Q3, not 2026-Q4` — so parent in the Quarter both belong to.
- **Unparented Outcomes come in two kinds.** `--emergency` means the work could not wait: no Why, no Verification, and never a Gap. Unflagged means nobody chose the Objective yet: a Parentless Outcome, and a Gap until it is parented or dropped.
- **Gaps are computed, never stored**, and there are exactly three: an Unaligned Goal (active, with no Objective in the reviewed Quarter), an Empty Objective (no Outcomes), a Parentless Outcome. An archived Goal is never a Gap. Review ends when the Quarter reports `no Gaps`.
- **Archiving means no longer pursued**, and is only sound once nothing under the Goal is `open`. `career archive` does not check this, so read the Backlog first.
- **Nothing is ever deleted.** An Outcome is `dropped`, an Idea is `discarded` and stays as the record of the decision, a Goal is archived. Use those words: Commitment, Claim, Verification, Why, Time Box, Backlog, Gap.

## Loops

**Work something.** `backlog` for the ranked queue — the top is next, and a `doing` Outcome keeps its place rather than rising. `progress start` when work begins, `progress box` each time a 25-minute box completes, `progress achieve` when the Claim holds or `progress drop` when it will not. Done when `backlog --all` shows the Outcome `(achieved)` or `(dropped)`.

**Shaping the plan.** `inbox add` to capture a thought without committing to it; `inbox promote` to turn one into a Goal, Objective or Outcome (the Idea survives, recording what it became), or `inbox discard` to close it. Then `objective add --goal <id>`, `outcome add --objective <id>`, and `quarter review` until the Quarter has no Gaps or each one is a deliberate emergency.
