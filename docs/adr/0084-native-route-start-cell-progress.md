# ADR 0084: Native route progress from an occupied starting cell

Status: verified in grouped Z8 and the relevant standalone publication build. 2026-09-13.

Z/Z4/Z5 native traces reproduced a collector stuck behind a stationary waiting NPC.
Even a side route stayed at path node 0: the center of the cell already containing the
collector. Its MoveControl target remained that occupied center and consumed the finite
route retry budget. After failure, release allowed the neighboring worker to collect the
remaining physical drop. This was not duplication or a task-specific mining policy.
The fixed close-obstruction geometry in Z6 reproduced the same failure independently.

NpcGroundNavigation now advances only the initial node when the grounded body is already
inside that cell, the next native node is at the same foot height, and the swept body
segment contains no solid block collision. It never skips a final or intermediate node,
changes the supplied destination, extends a lease, resets a budget or teleports a body.
Native MoveControl and entity collision remain authoritative. Supplied route bounds are
validated before advancement, including vanilla trimPath changes. Unsupported vertical
or obstructed corner segments retain normal native handling and bounded failure.

This uses public Path/Level APIs in the existing navigation mechanism. No mixin,
reflection, fake player, behavior dependency or persistence format change is required.
Verification includes two Core-only opposite-side routes, nine shared-miner cases including
the fixed close obstruction, full Core/Behavior native gates and grouped client/restart.

Z7 passed 45 Core units, 134 Core native cases and nine shared-miner cases after the
Z6 close-obstruction failure. Grouped Z8 and standalone publication validation also passed.

Z8: 379 units, 207 Behavior native tests, 24 separate-JVM checkpoints, 19 lifecycle
scenarios, 12 actual client cases and three-mod loading passed. Core AA separately
passed 45 units and 134 native cases; Behavior AB passed 328 units, 207 native cases
and 19 lifecycle scenarios against Core 74d2ba9.
