# Deterministic external missions

A rule pack proposes actions. An operation is durable work admitted through
`OperationSupervisionApi`. A mission connects explicitly admitted operations and
packs with verified completion predicates. No model is involved; all three remain
different concepts. An `actions` array does not wait for its first action to finish.

Use Studio's separate **File → Mission** window. Ordinary `.samgraph` files retain
their existing format. Mission projects use `.sammission`, format
`samcnpc-mission-studio`, version 1, with embedded editable rule packs. Open
`examples/missions/tutorial.sammission` for preparation → travel → return, or
`full.sammission` for equipment, wood, stone, exact coal, surface return and soil.
Coordinates in these examples describe the documented acceptance fixture; change
the authorized sources, work areas and destination before using another world.

## Installation and controls

Export a mission ZIP into `<instance>/resources/samcnpc/behaviors/`. This is a
SAMCNPC server-owned directory, not a vanilla datapack or resource-pack screen.

```text
/samcnpc behavior reload
/samcnpc behavior mission list
/samcnpc behavior mission start <npc-name-or-uuid> acceptance:tutorial
/samcnpc behavior mission status <npc-name-or-uuid>
/samcnpc behavior mission pause <npc-name-or-uuid>
/samcnpc behavior mission resume <npc-name-or-uuid>
/samcnpc behavior mission cancel <npc-name-or-uuid>
```

Normal NPC authority and operation admission checks apply. Each stage revalidates
the authorizing player, dimension, definition identity and exact task binding.
If the authorizing player is unavailable, the mission holds for review. Missions
are useful without the optional LLM mod.

## Bundle and document contract

ZIP entries are `behaviors/*.json`, `missions/*.json` and
`mission-manifest.json`. The manifest has `documentType: samcnpc:mission_bundle`,
`documentVersion: 1`, and exact `packs` / `missions` entry-name lists. Mission JSON
has `documentType: samcnpc:mission`, `documentVersion: 1`, a namespaced `id`,
`dimensionId`, `requirements`, `stages`, and optional `guards`.

Built-ins, loose external packs, ZIP packs and missions form one reload candidate.
Malformed input, duplicate IDs, unknown components or missing references reject
the complete candidate and preserve the last known-good registry. A mission JSON
placed among loose rule-pack JSON is rejected, never reinterpreted as a rule pack.

Limits: 1–16 stages and 1–16 requirements per mission; 0–3 declared guard packs;
20–72,000 ticks per stage; zero stage retries. A stage has one optional success
edge. Every stage must be reachable once from the first stage; cycles are invalid.
No failure edge is supported: failure means hold with an explicit diagnostic.
Operation-specific recovery keeps its existing independent bounded budgets.
The loader accepts at most 32 mission definitions; SavedData retains at most 256
NPC mission records. Existing ZIP and JSON size, nesting and path limits apply.

## Completion means a measured result

| Predicate | Meaning |
| --- | --- |
| `inventory_count` | Current carried matching quantity; minimum durability applies |
| `equipment_matches` | Current matching equipment in an explicit destination |
| `at_position` | Physical arrival within 0.25–2 blocks, clear supported standing space, no fluid |
| `destination_count` | Current exact item stock in an explicitly observed container |
| `soil_prepared` | The listed 1–64 cells are currently farmland |
| `task_success` | Historical receipt for this stage's exact completed operation |

Unknown observations are pending, not zero and never success. Operation stages
also require their exact bound task to report terminal success. Merely listing a
requirement on a stage is not proof. At the final stage, all current-state
requirements are observed again; consumed or removed stock cannot stay latched.
Additional yield belongs to the operation's accounting, not a fabricated mission
stock delta. Planting and harvesting are separate operations from soil preparation.

Only one stage runs per NPC. At most one transition occurs per tick. Required
built-in controller packs remain attached alongside the custom stage pack and
declared guards; existing channel arbitration governs all of them. Assignment
alone never turns `run_*_task` into a task constructor.

## Persistence and uncertainty

SavedData `samcnpc_behavior_missions`, version 1, retains bounded definition JSON
and hash, NPC and authorizing player UUIDs, exact task/revision, executed prefix,
remaining budget, pause/cancel state, previous assignments and pack fingerprints.
It stores no entity references, paths, chat history or per-tick observations.

A saved running stage reobserves and reconciles its exact task. A saved READY
transition is uncertain: it holds for review instead of replaying assignment.
An unknown task receipt, changed definition or changed/removed referenced pack
cannot launch the successor. A compatible explicit manual pause can resume without
resetting its remaining budget. Cancellation never starts the next stage.
Review requires inspecting the diagnostic, cancelling and deliberately admitting a
new mission if appropriate. World, task and mission saves are not an atomic
transaction, and this design does not claim exactly-once effects across them.
If global mission storage is corrupt or unsupported, its original data is preserved
and all autonomous Behavior execution holds until an operator repairs that file.
The unknown affected task bindings cannot safely be inferred from a corrupt store.
