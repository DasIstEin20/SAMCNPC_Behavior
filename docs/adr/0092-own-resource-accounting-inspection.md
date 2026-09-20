# ADR 0092: Own accounting and coordination inspection

Date: 2026-09-20. Status: accepted; clean/unit/native/client verification passed.

Planner inventory is current own-body state. A task ledger is a separate
checkpoint with provenance, and a coordination lease is neither item possession
nor permission. Mixing these would let supplied seeds satisfy a harvest goal,
spend protected transport stock or turn an old chest count into fresh world data.

Project existing per-item HarvestResources, ProducedResources, TransportLedger
and legacy ResourceProgress values as immutable bounded accounting. Preserve
actual counter names/units and uncertainty/reconciliation. NotTracked and
NotInitialized are different states. Do not forward old container counts,
endpoint stock snapshots or transfer before/after container contents.

Expose own harvest/container-step reservations through O(1) kernel lookups.
Inspection does not call advance(), acquire/renew/release, resolve contention or
look up the world. It copies only the requested NPC's scope, anchor, dimension,
queue times or lease expiry. Expired entries and backward-clock reads are omitted;
no contender's UUID/anchor escapes. Scope IDs may refer to standalone work rather
than a durable task. Existing authorization covers both kinds of inspection.

This is additive public observation. No task persistence version, arbitration
semantics, resource-credit semantics or planner executor changes.

Evidence: resource-evidence.json, 418 units, 210 native Behavior cases, 12 actual
client operations and both three-mod loading smokes; 737 source hashes verified.
