# 0116 — Bounded collection of a container's initial contents

Status: accepted, 2026-09-23.

Inventory supply replenishes named stock targets. It cannot express an authorized
request to collect everything from one chest without the caller guessing item IDs
and counts. Add `COLLECT` to Behavior's inventory operation, using its existing
container approach, transfer, resource accounting, return and persistence paths.
LLM only selects the typed order; it receives no container mutation capability.

At authorized assignment Behavior captures one observable, loaded container with
at most 64 slots. The immutable quota contains at most 16 distinct item IDs and at
most the caller's `maxItems` (1..2304). An unavailable, truncated, malformed or
oversized observation is rejected before task replacement or physical transfers.
An empty observed container has an empty quota. No observation means unknown.
The request is not silently truncated to fit a bound.

Each transfer is limited by the remaining captured quota. Existing carried stock
does not satisfy a withdrawal obligation. Later container replenishment cannot
increase the quota; source depletion, space exhaustion and uncertain foreign
effects retain ordinary explicit partial/failure outcomes. Quotas describe item
IDs and amounts, not persistent identities for individual stacks or block entities.
All original task budgets, interruptions and restart reconciliation still apply.

Inventory definition version 2 and catalog version 2 expose the new variant.
The decoder retains a strict version 1 shape containing only supply, unload and
pickup; persisted version 1 work remains valid with its original semantics.
Behavior API 3 identifies the added public sealed subtype. LLM requires that API
at startup. Core API 2 is unchanged. Automatic logistics policies keep their
existing explicit item filters; collection is an explicitly assigned operation.

Validation evidence stays in local autonomy files, outside published reports.
