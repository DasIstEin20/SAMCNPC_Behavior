# Verified visible stock observation — 2026-09-20

Core now provides an explicit one-item stock sensor for visible reachable vanilla
normal/trapped single/double chests. It refuses locks/ungenerated loot, unknown
chunks and unsupported containers, and never generates loot via getItem. Behavior
projects the read through current summoner/operator, range and dimension checks.
No automatic inventory scan or Supervisor runtime is enabled by this API alone.

Evidence: stock-evidence.json; clean build, 493 units (Core49/Behavior364/LLM80),
146 Core and 215 Behavior native cases, Core animation client, config GUI, dedicated
and integrated client with five stock reads after real deliveries on each side.
All 816 frozen source/build hashes and boundary/three-JAR guards PASS. Raw NBT stays
inside Core; serialization cost depends on existing item tags and must be considered
when scheduling explicit reads. See docs/STOCK_OBSERVATION.md and ADR0098.
LLM plan remains 23/36; next is the Supervisor runtime. Real model tests remain
USER_DEFERRED and authenticated skins MANUAL_PENDING/nonblocking.

# Exact amendment receipts — verified 2026-09-20

OperationSupervisionApi.amendmentReceipt reads the exact original payload's existing
PENDING/APPLIED/REJECTED/EXPIRED receipt. A missing request returns NOT_FOUND and is
never submitted. Current connected-summoner/operator, loaded-NPC, dimension and
range checks remain mandatory. No task persistence format change.

Canonical clean build: 449 units; 214 native Behavior cases; 12 actual client cases
with 9 receipt consumers; 30 LLM admission probes; full-context HTTP and three-mod
loading smokes; boundary/distribution PASS. All 774 frozen source/build hashes match.
Standalone Behavior clean build: 364 units PASS; all 529 module source files match
the canonical milestone. Core remains pinned to 53e3f25069e404f60d7d8c3d08c6bcc4d4802283.
Runtime proof comes from the canonical campaign, not a repeated standalone launch.
See docs/DECISION_VALIDATION.json, docs/OPERATION_API.md and ADR0095.

Current public surface also includes 16 typed operations, strict document/catalog
validation, own accounting/reservations, legal visual observations, generations and
bounded on-demand journals. Behavior remains independently useful without LLM.
Authenticated skins remain manual/nonblocking; actual LLM model profiles are deferred.

Earlier sections below retain evidence for their specifically dated snapshots.

# Own accounting and reservations — 2026-09-20

The authorized inspection projects per-item physical/produced/transport/legacy
accounting with uncertainty, plus own queued/held harvest/container reservations.
It does not reconcile ledgers, advance arbitration, renew leases or reveal
container stock/another NPC's coordination. Canonical clean build passed 418
units, 210 Behavior native cases, 12 actual client operations and both three-mod
smokes; all 737 frozen source/build hashes match. Core remains unchanged.
Standalone Behavior clean build and all 352 units passed; publication sources
match the frozen resource campaign. Evidence: docs/RESOURCE_VALIDATION.json. See docs/RESOURCE_INSPECTION.md.

# Verified opt-in visual inspection — 2026-09-20

Authorized public inspection can explicitly request bounded real-eye entity and
block/fluid observations. Occluded/invisible/spectator data and foreign private
body/container facts are excluded; missing chunks never load and remain unknown.
The ordinary body/task inspection does not scan automatically.

Canonical evidence: clean build, 412 units (47/346/19), 141 Core and 210 Behavior
native cases, 12 real client operations with 9 operator visual-inspection probes,
Core animation client and both three-mod GUI/HTTP smokes, boundary/distribution
checks and all 730 frozen source/build hashes PASS. Standalone clean build passed
346 Behavior units; source matches the verified visual milestone.
Core commit: 53e3f25069e404f60d7d8c3d08c6bcc4d4802283.
See docs/VISUAL_OBSERVATIONS.md, ADR 0091 and docs/VISUAL_VALIDATION.json.
Resource accounting/lifecycle/journal/ContextBuilder remain subsequent work.

# Authorized operation inspection — 2026-09-20

Current canonical milestone: clean build, 410 units (46 Core / 345 Behavior / 19 LLM),
209 required native Behavior tests, 12 real client operation scenarios including
9 operator inspection probes, three-mod GUI/client/dedicated HTTP-emulator smoke,
and source/boundary/distribution guards PASS. All 720 frozen source/build hashes
matched the tested campaign. Core is pinned to verified commit 74da575.

Inspection preserves actual definition parameters/versions for all 16 families,
measured progress units and uncertainty. It enforces existing actor/thread/range
checks and returns immutable body/task values. It adds no world scan or executor.
See docs/OPERATION_INSPECTION_API.md and ADR 0090. Standalone clean build and all 345 Behavior unit tests passed.

Earlier campaigns below retain their recorded source scope.

# Project state — 2026-09-20

Operation catalog/document milestone L0 is implemented and verified in the canonical
three-module workspace: sixteen families, eight changes, immutable parameter metadata,
bounded strict JSON decoding and generated schema using the same descriptors.
Authority, task revisions, expiry and existing semantic validators remain mandatory.

Evidence: canonical clean build; Core45 + Behavior337 + LLM6 unit tests;
208 required Behavior GameTests; 12 real client operation cases;
three-mod client/dedicated loading; 276 independent Draft202012 checks.
Source snapshot stayed unchanged across that campaign. Subsequent LLM configuration
campaign also passed all 337 unchanged Behavior unit tests.

Standalone publication clean build, all 337 Behavior unit tests and exportOperationCatalog
passed against pinned Core 74d2ba9. Generated catalog covers sixteen operation families.
P11.1/P11.7 are complete in the canonical plan (legacy104/112, Zoo23/23).
Authenticated two-account skins are human MANUAL_PENDING and do not block autonomous
completion. LLM functionality is a separate project and cannot control Core primitives.
See docs/OPERATION_API.md, docs/OPERATION_CATALOG_AUDIT.md and ADR 0086.

Observation generations verified (2026-09-20): 357 standalone unit tests; canonical 423 units, 211 Behavior Forge tests, 12 client scenarios (9 generation probes), three-mod client/dedicated smokes. [Contract](docs/OBSERVATION_GENERATIONS.md), [evidence](docs/LIFETIME_VALIDATION.json). Event subscriptions and LLM ContextBuilder remain unfinished.

2026-09-20: On-demand event subscriptions/journals verified. Standalone364 units; canonical430 units,214 Forge,12 client cases(9 event probes),actual dedicated shutdown closure. [Contract](docs/OPERATION_EVENTS.md), [evidence](docs/EVENTS_VALIDATION.json).
