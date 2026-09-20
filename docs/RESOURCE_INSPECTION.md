# Resource and reservation inspection

OperationInspectionApi includes per-frame resource accounting and own-NPC work
reservations after the same connected summoner/operator authorization.

A resource value is NotTracked, NotInitialized or Checkpoint. Checkpoints contain
at most 64 item rows and 16 named item counters per row. All lists are detached.
They are task evidence with an unknown measurement age, not current container
contents. Current carried items remain in the separate own-body inventory read.

PHYSICAL preserves initial/gathered/supplied/consumed/delivered/lost/retained.
PRODUCED adds stock/output/sourceOutput/deliveredOutput/protectedGathered where
recorded. TRANSPORT keeps initial/retained/withdrawn/delivered, incidental gains
and losses, cargo loss, initial cargo, current cargo and protected stock.
LEGACY_DELIVERY retains initial/confirmedRetained/delivered. These projections
do not reconcile a ledger or infer missing counts. Uncertainty and pending
inventory reconciliation remain explicit.

Reservations describe only this NPC's harvest-site and container-step claims
or queued requests (at most four rows across the two kernels). A scope ID usually
identifies a task; standalone work may use the NPC UUID. A row contains dimension,
anchor and either request times or expiry. It does not prove that a block still
exists, that resources are available, or that interaction is permitted.
Inspection never advances arbitration, renews a lease or reveals another NPC's
claim. Empty rows do not replace a fresh admission check.

Implementation decision: ADR 0092. Verification evidence: PROJECT_STATE.md.
