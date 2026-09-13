# Public Behavior API

Component catalog and candidate validation passed P11 Q. Observation/control is
verified in R3; typed amendments and generated schema in S. Validation covers native
world effects and actual client use. Typed assignment/full operation catalog remains
pending; these Kotlin APIs do not define or start a remote HTTP provider.

## Components and candidate packs

Call `BehaviorCatalogApi.snapshot()` to read immutable condition/action metadata. The
catalog contains 14 conditions and 23 actions, sorted by ID. Each entry declares its
kind, version, required channels, argument types, bounds, enum values and defaults.
Follow's optional `startDistance` defaults to `stopDistance + 2` and must be greater
than `stopDistance`; this is typed metadata, not an expression interpreter.

The catalog format, behavior document format and definition semantics are separately
versioned at 1. Use `BehaviorPackValidationApi.validateCandidate(json, source)` for the
actual bounded parser and semantic checks. Successful validation does not activate a pack
or assign an operation. Disk reload builds and validates the entire candidate before swap.
Catalog reads and candidate validation perform no world effects.

## Observations and task controls

`OperationSupervisionApi.observe(server, actor, npcUuid)` returns an `OperationReply`:
a Core `NpcActionResult` and, when authorized/available, an immutable observation.
A successful observation with `task == null` means this NPC has no durable task.
Unloaded NPC, denied permission, out-of-range access and invalid saved state are explicit
rejections. A rejected authorization never includes the task snapshot.

Call only on the authoritative server thread with the actual current connected
`ServerPlayer`. The summoner or an operator may inspect/control a loaded NPC within
256 blocks in the same dimension. An HTTP adapter must bind its trusted player context
and enqueue work onto that thread, then accept that permissions/world state can change.
A disconnected player object is not reusable authority. Existing assigned tasks can
continue while a player is offline; this API does not invent offline delegation.

The snapshot includes the exact task/objective UUIDs, definition/control revisions,
state, reason, bounded detail, pending amendment ID and at most three frames. Frames are
ordered primary first, active interruption last, and contain their own original duration,
remaining ticks, attempts used/allowed, waiting ticks and definition ID/version. These
are copied values, not mutable records or handles. They do not yet include every
resource-specific subtotal.

To control an observed task, submit `OperationControlRequest` with:

| Field | Contract |
| --- | --- |
| `taskId` | Exact task UUID from the observation. |
| `expectedControlRevision` | Exact nonnegative long revision. |
| `expectedDefinitionRevision` | Exact amendment revision, 0..32. |
| `issuedTick`, `expiresTick` | NPC game-time interval of 1..1200 ticks, not a wall clock. |
| `control` | `PAUSE`, `RESUME` or `CANCEL`. |

`OperationSupervisionApi.control(server, actor, npcUuid, request)` rechecks all of this
before using the same TaskService transition as a task command. Pause releases current
controls; resume reobserves; cancel retains completed world effects. None of these grants
fresh task time, attempts or inventory.

A delayed or duplicate request is rejected when the task or either revision changed,
together with a fresh authorized observation (normally CONFLICT; exhausted or terminal
state may be NOT_READY). It does not infer whether a
lost reply meant success. Reassess the new state before making another decision; never
automatically substitute fresh revisions into the old request. Terminal tasks cannot
be resumed, and controls for an old task cannot affect a replacement task.

## Persistence and boundaries

TaskStore v9 adds `controlRevision`. Older records lacking it migrate to zero; explicit
values are validated/preserved even in an older envelope. Malformed v9 revisions retain
the original record and safe idle. Successful pause/resume and the first terminal
transition advance the counter. At the maximum long value pause/resume fail closed and
terminal cancellation still works without wraparound.

These APIs expose no task executor, mutable NBT, Core action handles, command dispatcher,
script target or direct world mutation. They do not start an LLM provider or network
request. Assignment and the full operation parameter catalog remain pending P11 work.
Existing manual operation commands remain available.

See [ADR 0076](adr/0076-immutable-component-catalog.md),
[ADR 0077](adr/0077-versioned-operation-supervision.md) and
[behavior pack authoring](BEHAVIOR_AUTHORING.md).


## Typed corrections and receipts

OperationSupervisionApi.amend accepts an OperationAmendmentRequest with exact task ID,
request UUID, expected definition revision and finite game-time interval. The actor
comes from the trusted connected-player argument, never from the request payload.
OperationChange currently exposes Quantity (TOTAL/ADD), Recipients, Sources and
ExtendTime. OperationContainers copies 1..8 distinct positions and declares ORDERED
or NEAREST selection. Unsupported operation/change combinations explicitly reject.

This is the same amendment path as the existing operation commands: it can apply,
queue until a safe boundary, reject or expire. It never silently recreates the task.
OperationReply.amendment is an immutable receipt with APPLIED/PENDING/REJECTED/EXPIRED
and actual revision/detail. A successful historical query means receipt retrieval,
not necessarily APPLIED. Exact replay returns the existing matching receipt; the same
request UUID with a changed payload or actor cannot claim the older request's success.
Quantity correction retains completed physical work; time extension is applied once.
Replacement, tactics, reaction and logistics variants are not yet public.

## Schema for author tools

BehaviorSchemaApi.registeredSchema() returns a cached Draft202012 document generated
from the same structural schema and registered component catalog. It names allowed IDs
and argument fields, types, ranges and defaults. Related defaults/inequalities use
non-executable x-samcnpc annotations. The bounded runtime gateway additionally enforces
channel compatibility, rule uniqueness, recursive limits and file/world context.

The checked-in contracts/behavior-pack-registered.schema.json is the exported artifact.
See BEHAVIOR_AUTHORING.md for runnable documents and expected rejection diagnostics.
Campaign S compared 54 identical raw documents and all 16 builtins with an independent
schema validator; acceptance by schema alone never activates a pack.
