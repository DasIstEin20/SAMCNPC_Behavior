# ADR 0079: Compare-and-set public operation assignment

Status: verified in grouped campaign W. 2026-09-13.

Add immutable typed Navigate, Deliver(v2), Transport, Machine, Fish and Explore orders.
These six supported public types reuse existing operation IDs and definition versions;
manual commands still cover other families. This is a bounded public slice, not the
complete operation parameter catalog or a new JSON/network provider protocol.

OperationSupervisionApi.validateOrder converts to the existing closed TaskDefinition
and runs its normal pure validator. assign uses the R3 current-player, server-thread,
summoner/operator and loaded same-dimension/range gate, then compares the supplied
expectedPriorTaskId before calling the existing TaskService.assign. It never dispatches
commands, mutates the world on a background thread or creates a second task executor.
An active/paused task is explicitly rejected; replacing one requires explicit cancellation.

The store retains one latest task/terminal record per NPC, never evicts it and rejects
unresolved/corrupt state. Null prior ID is therefore allowed only before the first record.
A lost successful assignment reply cannot cause another assignment on replay, even after
that task finishes or the server restarts: its retained task UUID differs. The transport
result is conflict with a fresh authorized observation, not a claim of exactly-once receipt.
Requests have a finite 1..1200 game-tick lifetime. No request ledger or persistence format
change is needed; existing v9 and its migration remain authoritative.

Task definitions and budgets are data; the caller can validate bad values without world
access. Container and machine-feed lists are defensively copied and bounded. A valid
definition does not imply a loaded machine, available stock, a safe route or authority;
those remain the existing assignment/execution checks. Public return targets, reserves,
sides, quotas and time limits map exactly to their established definition fields.

Required evidence: invalid/stale/expired/conflicting assignment with unchanged state,
physical navigation/delivery, public-only consumer compilation, exact conversion/bounds,
and ordinary-machine/fishing/exploration/transport persistence plus actual client use.
The remaining operation families and complete parameter catalog stay open P11.1/.7.

W evidence: p11-assignment-w-evidence.json, source194d8c83eb5de5a993c6d9a833e618c5ba19d9db278ecd47b3fde6ba666bd63a; 363 units,206 native,24 separate-JVM checkpoints,19 lifecycle,12 actual client cases and three-mod loading passed.
