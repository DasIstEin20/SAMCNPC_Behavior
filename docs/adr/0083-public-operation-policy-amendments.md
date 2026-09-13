# ADR 0083: Public operation policy amendments

Status: verified in grouped Z8 and the relevant standalone publication build. 2026-09-13.

Expose Replace, Tactics, Reaction and Logistics through the same typed amendment
request and exact receipt path as quantity, sources, recipients and time extension.
These inputs convert to the existing validators, detached candidate preparation and
safe-boundary commit. No executor, authority, replay ledger or persistence format
changes. Inventory and combat conversion helpers are shared with typed assignment.

Replacement explicitly preserves the objective or starts a bounded new objective
history entry. It cannot change operation kind, dimension, retry limits or time budget;
resource/method changes require NEW_OBJECTIVE where the existing contract requires it.
Machine, fishing, exploration, inventory and combat missions retain their narrower
supported amendments. Rejected combinations must stay explicit.

Reaction inputs retain subject/anchor/filter requirements, hard weapon permissions,
finite interruption and cooldown. Optional logistics uses bounded supply, unload and
pickup requests, a fixed anchor and a finite return budget. Supply/unload reserve
checks prevent a loop that immediately unloads stock just requested for work.

Verification targets exact payload replay and invalid bounds in unit tests; real cargo
replacement with old/new resource accounting; queued navigation replacement during
inventory work and store reload; subject protection/filtered reaction during delivery;
and actual client bow, potion and shield effects enabled by public tactics amendment.
All existing physical assertions remain. Complete parameter discovery is separate P11 work.

Z8: 379 units, 207 Behavior native tests, 24 separate-JVM checkpoints, 19 lifecycle
scenarios, 12 actual client cases and three-mod loading passed. Core AA separately
passed 45 units and 134 native cases; Behavior AB passed 328 units, 207 native cases
and 19 lifecycle scenarios against Core 74d2ba9.
