# 0118 — Bounded hoe-only field preparation

Status: accepted, 2026-09-23.

The existing Farm operation promises crop production, delivery and optional replanting.
Treating bare farmland as crop yield would violate that contract. A request to prepare
soil therefore uses `samcnpc:prepare_field`, a separate typed Behavior operation.

Its explicit area is a single plane of soil blocks with a bounding footprint of at
most 64 cells, including exclusions. Anchor, travel radius, optional return and common
task budgets remain explicit. No seeds, irrigation, clearing, harvesting or tool
acquisition are implied. Behavior uses a carried hoe and Core's physical block-use
primitive. Farm and field preparation share the same small physical-use helper.

The registered action retains normal channel arbitration, work claims and bounded
approach. Runtime state records confirmed farmland, a maximum of two native-use
attempts per cell, a physical inventory ledger, stop reason and return/verification
phase. Existing farmland can satisfy a cell without a fabricated use receipt. Missing
tools, blocked/unsupported soil and unavailable observations stop finitely. An uncertain
external effect terminates without replay or arbitrary rollback.

Pause/load rechecks saved soil before new effects. Completion requires observation of
the whole authorized plane after physical return, with air above and certain inventory.
The operation does not promise permanent hydration or protection against later changes.
Geometry is fixed during captured work; existing time, tactics and reaction controls
remain available. Replacing geometry requires a new task.

TaskStore format 10 admits this new frame/state and rejects it in older envelopes;
versions 1–9 retain their existing migrations. The operation catalog is version 4,
Behavior API identifier 5, and LLM requires Behavior API 5. The public order, JSON and
experimental SAM expression decoders all reach the same Behavior validator/executor.
Trusted goal constraints additionally require an allowed soil area, return and explicit
block-work permission. No quantity/yield semantics are accepted for this operation.

Frozen previous language corpora remain unchanged. A separate field extension covers
Polish, English, negation and missing area. Runtime success must be established by
native physical tests and real-provider evidence; scripted candidates are not model
comprehension evidence. Validation receipts are local and are not publication artifacts.
