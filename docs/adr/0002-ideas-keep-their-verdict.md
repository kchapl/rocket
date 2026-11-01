# A promoted or discarded Idea is kept, not consumed

An Idea's verdict had nowhere to go: promoting or discarding it could either consume the record — leaving the inbox holding only undecided thoughts — or leave it in place with the outcome written down. We keep it. An Idea gains a `disposition` (`pending | promoted | discarded`), a promoted Idea keeps the id of what it became, and both terminal dispositions are final. The hierarchy can therefore be traced down to the thought that started it, and a rejection remains a thing that happened rather than an absence.

## Considered Options

- **Consume the Idea on verdict** (simplest: the inbox holds only undecided thoughts, `discard` deletes the row). Rejected on reversibility alone. Hiding rows you no longer care about costs nothing and needs no decision; restoring deleted rows is impossible, and the need for them is only noticed later, when the evidence is already gone.
- **Keep promoted Ideas, delete discarded ones.** Rejected: the rejections are the more interesting record. "I capture this every quarter and talk myself out of it" is invisible if the rejections were deleted, and that pattern is exactly what a quarterly review should be able to see.

## Consequences

- `inbox list` shows `pending` Ideas by default; `--all` includes the rest.
- Ideas are never deleted, which matches Career Goals being archived rather than deleted — except that an Idea needs no separate archived flag, since the verdict itself is the archive.
- `trace` stays upward-only. Provenance is read from the Idea side, not walked down through the hierarchy.
- A promoted Idea's link records what it became at the time, not what that object is now: the target may since have been dropped or archived, and the link does not follow it.
