# rocket

The domain language for a single-user tool that sets long-term career ambitions and traces the work back up to them. Read this before naming anything; use these terms in code, commands, issue titles and test names rather than inventing synonyms.

This file is a glossary: it says what each term **is**, never what the tool **does** with it. Behaviour, schema and command surface are owned by [DESIGN.md](DESIGN.md).

## Language

The tiers nest and Ideas do not. **Career Goals**, **Quarterly Objectives** and **Outcomes** form a commitment hierarchy ordered by scope, each tier committed inside the one above it — except the Outcomes that sit outside it: an **Emergency Outcome** is committed to a Quarter with no Objective above it, and so is a **Parentless Outcome**, promoted from an Idea before anyone chose one. **Ideas** sit outside that hierarchy entirely: capturing one commits nothing, so an Idea has no tier, no Quarter, and nothing to trace upward to. It acquires a place in the hierarchy only by being Promoted — and even then the Idea itself is kept, recording what it became, rather than being consumed.

### The three tiers

**Career Goal**:
The top tier: a long-term ambition that outlives any single Quarter (for example, "become a staff engineer"). It has no completion date of its own: it is never completed, only archived, and archiving means it is no longer pursued.
_Avoid_: Career objective, ambition, theme

**Quarterly Objective**:
The middle tier: a concrete result committed to within one Quarter, and the level at which progress is actually reviewed. It is composed of Outcomes and has no progress of its own beyond theirs; it has no Status of its own either, so whether it is met or abandoned is read off its Outcomes rather than recorded. No Objective spans Quarters: the same intention committed again next Quarter is a different Objective.
_Avoid_: Objective (bare), goal, initiative

**Outcome**:
The bottom tier and the only unit of work: something specific that is to be **achieved**. A Quarterly Objective is composed of zero or more Outcomes.
_Avoid_: Task, ticket, work item, Implementation Outcome

**Emergency Outcome**:
An Outcome committed to a Quarter with no Quarterly Objective, flagged as an emergency because the work could not wait to be filed under one. The flag is what separates it from an Outcome that is merely not yet parented. It carries no Why and no Verification, because the reason it exists is that it could not wait.
_Avoid_: Urgent task, parentless task, standalone outcome

**Time Box**:
A single 25-minute unit of effort recorded against an Outcome at the moment the work is done. Effort is counted in Time Boxes; nothing else measures effort. Its length never varies: a box is always 25 minutes, so box counts stay comparable across Quarters. A Time Box belongs to the Quarter it was recorded in, not to the Quarter its Outcome now sits in, so an Outcome's total effort and a Quarter's total effort are different questions.
_Avoid_: Pomodoro, session, work block, time entry

### Why and Verification

**Why**:
The reason a Career Goal, a Quarterly Objective or an Outcome is worth doing, in the words of whoever committed to it: the answer to "why does this matter?", not a description of the work.
_Avoid_: Rationale, reason, justification, motivation

**Verification**:
A change in the world that would show a Career Goal, a Quarterly Objective or an Outcome was reached — stated so that someone else could check it without asking, and measurable where the thing admits measurement. An entity may have several, and may have none. A Verification is a statement about the world, not a task: nothing records whether it holds.
_Avoid_: Acceptance criteria, definition of done, success metric, test

Verification is not Status: an Outcome can be `achieved` with no Verification at all, and can have its Verification met while staying `open`. Neither field implies the other.

### State of an Outcome

**Status**:
An Outcome's standing as a commitment: `open`, `achieved` or `dropped`. It is the only place achievement is recorded, so "is this done?" is a question about Status alone. `achieved` and `dropped` are terminal.
_Avoid_: State, completion, resolution, lifecycle

**Stage**:
Where a live Outcome's work stands: `todo` or `doing`. It describes work in flight and has no terminal value; an Outcome leaves the Stage ladder as soon as its Status leaves `open`.
_Avoid_: Phase, step, workflow state

**Progress**:
The verb for advancing an Outcome, not a name for either field: a `progress` action moves Status or Stage according to what it records.
_Avoid_: Using "progress" as the noun for Status or Stage

### Capture

**Idea**:
A raw, unscoped thought captured before it is shaped into anything: it has no Quarter, no parent and no commitment, and it is not yet a unit of work.
_Avoid_: Note, inbox item, todo, draft

**Promote**:
The act of turning an Idea into a Career Goal, a Quarterly Objective or an Outcome. An Idea is either Promoted or Discarded; there is no third fate, and the Idea itself outlives the verdict as the record of it.
_Avoid_: Convert, migrate, escalate

**Disposition**:
What became of an Idea: `pending`, `promoted` or `discarded`. It answers a question about the Idea itself, never about what the Idea grew into; `promoted` and `discarded` are final.
_Avoid_: Idea status, verdict, state

### Working modes

**Planning Mode**:
The mode in which the hierarchy is shaped: Career Goals, Quarterly Objectives, Outcomes and Ideas are reviewed and set. It asks what is worth committing to, and on its own moves nothing that is already in flight.
_Avoid_: Strategy mode, planning phase, admin mode

**Throughput Mode**:
The mode in which committed Outcomes are worked. It asks what comes next, records Time Boxes and moves Status as work proceeds; it never adds to or restructures the hierarchy.
_Avoid_: Execution mode, doing mode, work mode

**Backlog**:
The current Quarter's single ordered queue of open Outcomes, from next to last. Work in flight keeps the place its rank gives it rather than rising to the top, so that order is the only ordering Outcomes have.
_Avoid_: To-do list, queue, task list, priorities

### Time

**Quarter**:
A calendar quarter with a start and an end date (for example `2026-Q3`), the container that Objectives and Outcomes are committed into. A Quarter closes at its calendar boundary whether or not anyone is watching; the closed Quarter is the unit of review.

**Gap**:
A hole in the reviewed Quarter's plan. Gaps are surfaced by review and never stored; there are exactly three kinds, each defined by what is missing beneath something.
_Avoid_: Issue, warning, alert, problem

**Unaligned Goal**:
A Career Goal that is still being pursued but has no Objective committed in the reviewed Quarter. An archived Goal is never a gap — archiving already answered the question the review is asking — while a Goal left merely idle, active but uncommitted, is exactly what this gap is for.
_Avoid_: Orphan goal, idle goal, stale goal

**Empty Objective**:
A Quarterly Objective with no Outcomes.
_Avoid_: Hollow objective, bare objective

**Parentless Outcome**:
An Outcome with no Objective that is not flagged as an emergency: work promoted into an Outcome before anyone chose the Objective it serves. It is a Gap until it is parented or dropped, never a resting state.
_Avoid_: Orphan outcome, loose outcome

**Carried In**:
The part of an Outcome's effort recorded in an earlier Quarter than the one it now sits in. It is a fact about when the Time Boxes happened, not a separate store of work.
_Avoid_: Rolled over, backlog debt, legacy effort

## Notes on naming

**Objective** alone means a Quarterly Objective, and **Outcome** alone is the bottom tier — it has no longer form. A qualifier on the upper two tiers says which scope is meant, so don't let Goal, Objective and Outcome collapse into each other. Achievement is never a Stage: a **Status** is what can be achieved, and a Stage is only ever `todo` or `doing`.

The qualifiers do not all name a horizon. The tiers are ordered by **scope**, not by timespan: Quarter is the only one of the three that is a time container, and it is the unit of review; a Career Goal is unbounded; an Outcome has no horizon of its own — it lasts until it is achieved or dropped, and its effort is counted in Time Boxes rather than in days.

**Implementation** is not one of our words: no tier, entity or command is named after it. The bottom tier is an **Outcome**, and the modes are **Planning** and **Throughput**.

An **Emergency Outcome** is still an Outcome, not a fourth tier: it is unparented, not demoted or promoted. Likewise an **Idea** is not a stub Outcome — it accrues no effort and belongs to no Quarter; only what it grows into does. And an Idea's **Disposition** is not an Outcome's **Status**: a Status says whether a commitment is still live, a Disposition says what happened to a captured thought.
