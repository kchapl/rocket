# rocket

The domain language for a single-user tool that sets long-term career ambitions and traces implementation work back up to them. Read this before naming anything; use these terms in code, commands, issue titles and test names rather than inventing synonyms.

## Language

### The three tiers

**Career Goal**:
The top tier: a long-term ambition that outlives any single Quarter (for example, "become a staff engineer"). It has no completion date of its own; it is reviewed, not closed, and it is archived rather than deleted so the work recorded against it is never orphaned.
_Avoid_: Career objective, ambition, theme

**Quarterly Objective**:
The middle tier: a concrete result committed to within one Quarter, and the level at which progress is actually reviewed. It is composed of Implementation Outcomes and has no progress of its own beyond theirs.
_Avoid_: Objective (bare), goal, initiative

**Implementation Outcome**:
The bottom tier and the only unit of work: something specific that is to be **achieved**. A Quarterly Objective is composed of zero or more Implementation Outcomes.
_Avoid_: Outcome (bare, when the tier is ambiguous), task, ticket, implementation item

### Time

**Quarter**:
A calendar quarter with a start and an end date (for example `2026-Q3`), the container that Objectives and Outcomes are committed into. A Quarter closes at its calendar boundary whether or not anyone is watching; the closed Quarter is the unit of review.

## Notes on naming

An **Objective** alone means a Quarterly Objective; **Outcome** alone means an Implementation Outcome. The qualifier carries the tier, so don't let the two nouns collapse into each other.
