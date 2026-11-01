# Achievement is recorded in `status` only; `stage` has no terminal value

Outcomes carried two state fields that each listed `achieved` — a lifecycle `status` (`open | achieved | dropped`) and a work `stage` (`todo | doing | achieved`) — so an Outcome could be open and achieved at once, and no rule said which field answered "is this done?". We kept both fields and made their value sets disjoint: `status` is the sole record of achievement and owns every terminal answer, while `stage` is `todo` or `doing` only and stops moving once `status` is terminal. Both fields survive because they answer different questions in different modes: Planning asks whether the commitment is still live, Throughput asks what is in flight.

## Considered Options

- **One merged state machine** (`todo | doing | achieved | dropped`). Simpler and tempting, but it puts `dropped` in the same ladder as `doing`, and it cannot represent an Outcome achieved without ever being started — work that turned out to be unnecessary, or was achieved incidentally. `stage: todo` with `status: achieved` is a legal and ordinary state.
- **Deriving `status` from `stage`.** Rejected: `dropped` is a decision about the commitment, not a position in a workflow, so there is nothing to derive it from.

## Consequences

- A terminal `status` freezes `stage`, and no Time Box may be recorded after it.
- Rollover and the "is this done?" check both read `status` alone, so the carry-forward rule and the done-check cannot drift apart.
- `trajectory` JSON compatibility: if its schema expects an `achieved` stage, that value is projected at the export boundary rather than stored. Unverified — Phase 3 must confirm whether the projection is needed at all.
