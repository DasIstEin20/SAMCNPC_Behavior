# ADR 0077: Versioned operation supervision

Status: accepted; verified in P11 R3. 2026-09-13.

Expose immutable operation observations and bounded pause/resume/cancel through public
OperationSupervisionApi. Only the current connected ServerPlayer, on the authoritative
server thread, may supervise a loaded NPC in the same dimension within 256 blocks.
Summoner/operator authorization is checked on every call before exposing task information.
A future HTTP adapter must bind the trusted player context and enqueue work; model data
does not supply an execution bypass. Offline continuation of existing tasks is unchanged.

A control request names the exact task UUID, control revision and definition revision, plus
an issue/expiry interval of at most 1200 game ticks. Existing TaskService performs the effect.
A duplicate request after an uncertain reply returns conflict with a fresh observation;
it never reports inferred success or automatically retries with renewed revisions. This
compare-and-set contract needs no unbounded request ledger. Definition amendments retain
their existing independent revision/receipt gate.

TaskStore moves from v8 to v9. Every record persists a nonnegative long controlRevision.
Successful pause/resume and the first terminal transition advance it; ordinary progress
does not. All command/API paths share TaskRecord transitions. Older records without this
field migrate to zero. If an older envelope already contains it, validate and preserve the
explicit value instead of resetting it. A v9 record with a missing, mistyped or negative
revision is preserved as invalid and cannot execute. At Long.MAX_VALUE further pause/resume
is rejected; terminal cancellation remains possible without overflow and cannot be undone.

Observations copy at most three frames, original/remaining budgets, reasons, objective,
definition/control revisions and pending amendment identity. They expose no mutable record,
NBT, world object or Core action handle. Resource-specific counters, operation catalog and
typed assignment/amendment documents remain separate slices, not empty API placeholders.

Validation must cover old/current persistence, malformed counters, stale and expired
controls, command/API interleaving, authorization/disconnect/range, physical control release,
continued task budgets and a separate-JVM reload. No generic executor policy is replaced.

R3 passed clean/build/static/distribution, 349 units (45/302/2), 196 native Behavior
cases, 24 exact checkpoints across separate JVMs, 18 lifecycle cases and 12 actual client
cases. Nine client work families exercised public pause/resume as an actual operator.
Native tests exercised summoner access, unauthorized/level-0 op/disconnected/range/thread
rejection, stale/expired controls, reload, physical release/resume and replacement-task
identity. R and R2 fixture failures are retained; no production checks were removed.
Evidence: autonomy/run-20260912-zoo/p11-supervision-r3-evidence.json.
