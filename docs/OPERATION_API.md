# Public Behavior API

Component catalog and candidate validation passed P11 Q. Observation/control is
verified in R3; typed amendments and generated schema in S. Validation covers native
world effects and actual client use. All sixteen typed assignment families passed grouped campaigns W/X/Y. A complete operation parameter catalog and strict definition-document decoder were added
on 2026-09-20; grouped clean, native, real-client and three-mod loading verification passed. These APIs do not start a provider.

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
request. Assignment covers the sixteen families below. The new operation catalog and
definition document format are described below; authority remains in this gateway.
Existing manual operation commands remain available.

See [ADR 0076](adr/0076-immutable-component-catalog.md),
[ADR 0077](adr/0077-versioned-operation-supervision.md) and
[behavior pack authoring](BEHAVIOR_PACKS.md).


## Typed corrections and receipts

OperationSupervisionApi.amend accepts an OperationAmendmentRequest with exact task ID,
request UUID, expected definition revision and finite game-time interval. The actor
comes from the trusted connected-player argument, never from the request payload.
OperationChange exposes Quantity (TOTAL/ADD), Recipients, Sources, ExtendTime, Replace,
Tactics, Reaction and Logistics. All eight variants passed grouped Z8 verification. OperationContainers copies 1..8 distinct positions and declares ORDERED
or NEAREST selection. Unsupported operation/change combinations explicitly reject.

This is the same amendment path as the existing operation commands: it can apply,
queue until a safe boundary, reject or expire. It never silently recreates the task.
OperationReply.amendment is an immutable receipt with APPLIED/PENDING/REJECTED/EXPIRED
and actual revision/detail. A successful historical query means receipt retrieval,
not necessarily APPLIED. Exact replay returns the existing matching receipt; the same
request UUID with a changed payload or actor cannot claim the older request's success.
Quantity correction retains completed physical work; time extension is applied once.
Replacement, tactics, reaction and logistics retain their existing operation-specific
restrictions; see the policy amendment contract below.

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

## Typed operation assignment

`OperationSupervisionApi.validateOrder(order)` is pure and runs the existing definition
validator. `assign(server, actor, npcUuid, request)` uses the same authorization and
server-thread boundary as observation/control, then the existing task assignment path.
No caller can replace an active or paused task implicitly: cancel it first, reobserve,
and decide whether to assign a replacement.

| Public order | Definition | Supplied intent |
| --- | --- | --- |
| `Navigate` | `samcnpc:navigate` v1 | Destination, speed, arrival distance, budget |
| `Deliver` | `samcnpc:deliver` v2 | Carried item, quantity, chest, anchor, retained inventory, budget |
| `Transport` | `samcnpc:transport` v1 | Source/destination choices, item, quantity, reserves, travel bounds, return, budget |
| `Machine` | `samcnpc:machine` v1 | One to four sided input ports, output port, quantities, polling, timeout, travel/return, budget |
| `Fish` | `samcnpc:fish` v1 | Water and stance, catches, collection wait, travel/return, budget |
| `Explore` | `samcnpc:explore` v1 | Anchor, area, cell spacing/count, vertical/chunk limits, heading, budget |
| `OperationCombatOrder.Attack` | `samcnpc:attack` v2 | Exact target, anchor/leash, player permission, hard weapon limits, tactics, budget |
| `OperationCombatOrder.Defend` | `samcnpc:defend` v1 | Protected subject or bounded area/filter, duty duration, return, tactics, budget |
| `OperationCombatOrder.AreaAttack` | `samcnpc:attack_area` v1 | Explicit filter, defeat quota, anchor/leash, return, tactics, budget |
| `OperationCombatOrder.Patrol` | `samcnpc:patrol` v1 | Bounded route, rounds, dwell, reaction/subject/support/filter, return, tactics, budget |
| `OperationInventoryOrder` | `samcnpc:inventory_work` v1 | Supply, unload or pickup; stock reserves, anchor, work/return time, step budget |
| `OperationHarvestOrder.Mining` | `samcnpc:mine` v1 | Work box/exclusions, method/tunnel/access, resources/outputs, counting basis, quota, containers, return, budget |
| `OperationHarvestOrder.Farm` | `samcnpc:farm` v1 | Crop, area, harvest/replant mode, cycles, seed sources/reserve, quota, recipients, return, budget |
| `OperationHarvestOrder.Planting` | `samcnpc:plant_trees` v1 | Species, layout or explicit bases, area/exclusions, sources/reserves, quantity, return, budget |
| `OperationHarvestOrder.Food` | `samcnpc:food` v1 | Drops, berries, stored food or explicit hunting; output filter, quota, sources/recipients, reserves, return, budget |
| `OperationHarvestOrder.Lumberjack` | `samcnpc:lumberjack` v2 | Wood filter, quota, separate supplies/output, reserves, search bounds, optional bounded replant, return, budget |

These types are immutable data. Container choices and machine feeds copy and bound their
input lists. Validation preserves the existing operation-specific ranges and relationships;
it does not promise that a machine exists, stock is available, or a route remains safe.
`OperationType` exposes the stable operation ID and definition version for all sixteen types.
OperationCatalogApi exposes the full machine-readable operation parameter catalog.
OperationDocumentApi decodes these order/change documents. Behavior component metadata
and its registered JSON schema separately cover behavior-pack conditions/actions.

Observe before assigning. Use `observation.task?.taskId` as `expectedPriorTaskId` and the
NPC's observed game tick to form `issuedTick`/`expiresTick` with a lifetime of 1..1200 ticks.
A null prior ID is valid only when the NPC has no retained task record. Successful
assignment retains a new task UUID. Replaying the original request therefore conflicts,
even after completion or a server restart; it cannot silently start the work twice.

A conflict includes a fresh authorized observation when available. It is not a historical
success receipt. After a lost reply, inspect the task instead of changing the old request's
prior ID and blindly retrying. Task time and retry budgets belong to the operation and
are separate from the request's expiry window. This does not change TaskStore v9.

See [ADR 0079](adr/0079-compare-and-set-public-operation-assignment.md). The separate LLM
module may consume these public types later; no network client or provider is started here.

## Policy amendment contract

`Replace(order, PRESERVE)` retains the objective's accounting. Use `NEW_OBJECTIVE` when
changing the resource or work method would invalidate that accounting. Completed work
is retained in bounded objective history. A replacement must use the current task's
operation kind, dimension and full original budget. It cannot reset attempts or extend
time. Use `ExtendTime` for an explicit finite addition; cancel and assign a new task for
a different operation kind. The history permits at most eight previous objectives.

`Tactics` preserves task identity, remaining time and completed effects. Weapon allowance
is a hard constraint; weapon preference does not override it. Shield, healing, equipment
and retreat settings still require real inventory and supported Core actions.

`Reaction` configures ordinary work interruptions: PASSIVE, RETALIATE, PROTECT_SUMMONER,
PROTECT_UNIT or AREA. Subject protection requires an explicit subject and fixed anchor;
AREA requires a fixed anchor and nonempty entity filter. Leash, interruption duration,
cooldown and player permission remain explicit. Combat missions use their own policies.

`Logistics` optionally supplies stock, unloads excess and collects allowed nearby items
while preserving the primary goal. Enabled side work requires an anchor and finite
travel/work/return/step bounds. Supply target and unload reserve must not create a loop.
Disabled logistics has no subrequests and no anchor. Side work can finish before a queued
primary-goal correction applies; PENDING is a receipt, not confirmation of completion.

Fishing, machines and exploration permit only tactics, reaction and time amendments.
Inventory work fixes its captured resource/route request. Combat missions permit tactics,
time and, for area attack, the existing defeat quota. These restrictions are checked again
against the live task. A structurally valid order may still be an invalid replacement.
A maximum of 32 amendment receipts bounds each task; integrations must handle rejection.

## Integration sequence

1. Read the catalog/schema for behavior packs, or construct a known typed operation order.
2. Validate the order without world access using `validateOrder`.
3. On the server thread, resolve the current connected player and observe the NPC.
4. Assign using the observed prior task ID and a finite game-time request interval.
5. Supervise copied observations; submit a correction with the exact task/revision and a
   new request UUID. Reuse that UUID only to recover the receipt of the exact same request.
6. When a correction conflicts, inspect current state before deciding again. Do not silently
   change revisions or request IDs and retry an obsolete decision.

A future LM Studio/Ollama/OpenAI-compatible adapter belongs in the separate LLM module.
It must convert output into these bounded values and schedule authorized work on the
server thread. No provider, HTTP credentials, command execution or model call is added
by this API. Offline delegation and supervision outside the current 256-block access
boundary are not provided. Existing tasks continue according to their own task budgets.

See [ADR 0081](adr/0081-public-combat-and-inventory-orders.md),
[ADR 0082](adr/0082-public-harvest-order-boundary.md) and
[ADR 0083](adr/0083-public-operation-policy-amendments.md).

## Operation catalog and JSON definition documents (L0)

`OperationCatalogApi.snapshot()` returns one cached immutable catalog, version 1.
It includes all sixteen `OperationType` identities/definition versions, nested parameter
types, bounded lists, units, defaults, field relations, completion bases and candidate
amendment kinds. The task-state and reason vocabularies use the current runtime enums.
See [the audit](OPERATION_CATALOG_AUDIT.md) and [ADR 0086](adr/0086-operation-catalog-and-definition-documents.md).

`OperationDocumentApi.catalogJson()`, `orderSchema()` and `changeSchema()` return cached
strings. Generate author artifacts with `gradlew.bat :samcnpc-behavior:exportOperationCatalog`
(or `gradlew.bat exportOperationCatalog` in the standalone Behavior checkout).
Files appear under `build/operation-contracts/` in that module.

An order definition has four fields:

```json
{
  "documentVersion": 1,
  "type": "samcnpc:deliver",
  "definitionVersion": 2,
  "parameters": {
    "dimensionId": "minecraft:overworld",
    "destination": {"x": 3, "y": 64, "z": 0},
    "itemId": "minecraft:cobblestone",
    "quantity": 32,
    "anchor": {"x": 0, "y": 64, "z": 0}
  }
}
```

`decodeOrder(json)` returns `OperationDocumentResult.Accepted<OperationOrder>` or a
bounded `Rejected(code, detail)`. This only validates a definition. Bind a real current
actor/NPC and the observed task/expiry envelope before calling `assign`; no such
identity or authority is accepted from this document. There is no automatic disk
loader or network endpoint for orders. Decode at an input boundary, never per NPC tick.

A change uses the same document version and an uppercase change discriminator:

```json
{"documentVersion":1,"type":"EXTEND_TIME","parameters":{"ticks":1200}}
```

`decodeChange(json)` returns a typed `OperationChange` after the established semantic
checks for that change. It does not promise compatibility with the current task:
`amend` still enforces its revisions, expiry, operation-specific restrictions, receipts,
safe work boundary, history limits and world observations. REPLACE embeds a complete
order document, retains the operation family/dimension and cannot reset retry budgets.

All objects reject unknown/duplicate keys; integers are checked without rounding.
Input is at most 64 KiB UTF-8 and reuses the bounded pack lexical parser (depth 32,
16384 nodes, strings at most 512 code points, numeric tokens at most 64 characters).
Null is accepted only where advertised. Optional compound fields normalize their
listed defaults; explicit null is distinct from omission. Fish returnTo defaults to
anchor but may explicitly be null. Missing defense budget defaults to dutyTicks+400;
an explicitly supplied budget object uses its own listed defaults.

Schemas are structural Draft 2020-12 documents. `x-unit`, `x-defaultFrom` and
`x-semanticRelations` are data annotations, not an executable expression language.
Use the runtime decoder/validateOrder for geometry, totals, method-dependent fields,
resource relationships and other semantics. Assignment additionally checks the world.
A successful generic schema validation does not mean a resource exists, a chest is
available or a player is permitted to assign work.

The author corpus contains one document for each family and thirty explicit variants
under `src/test/resources/operation-documents/` in the Behavior module. These are
finite examples, not world fixtures or automatic commands. No source position or
resource should be treated as an observation of the player's actual world.

## Detailed read-only inspection

OperationInspectionApi captures own-body details, actual versioned task parameters
and measured family progress under the same actor authorization. See
[operation inspection](OPERATION_INSPECTION_API.md); no world object or executor
handle crosses that boundary. World sensors and event journals are separate work.
