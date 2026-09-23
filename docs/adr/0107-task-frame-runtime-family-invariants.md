# 0107: Validate TaskFrame runtime families at boundaries

Status: accepted, 2026-09-23. Audit A8.

TaskCodec already rejects unrelated persisted states, but a mutable in-memory
TaskFrame could associate navigation with combat, inventory or another family's
runtime. Constructor and store regressions reproduced both admissions.

Add an explicit small family check at construction, store admission, observation
and dispatch. Lazy capture is retained. Lumberjack plus its recorded replant
definition and planting state remains legal. Do not validate each individual
setter: amendments can update related fields in sequence on the server thread.

A mismatch ends active execution with STATE_MISMATCH and releases its controls
before physical work. Do not manufacture inventory outcomes from an unrelated
definition. Retain the invalid payload: existing strict load/quarantine preserves
it on restart instead of silently discarding it or trusting it as runnable state.
No persistence version, interruption model, budgets or runtime engine is replaced.
