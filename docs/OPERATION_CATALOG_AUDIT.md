# Operation catalog audit — L0 / 2026-09-20

The public source of operation identity/version is OperationType. Numeric and
structural metadata is in the immutable OperationCatalog; cross-field and runtime
checks remain in the established validators. The table records inspected source,
not a claim that every world outcome was newly exercised.

| Operation | Version | Definition validator | Completion basis |
|---|---:|---|---|
| navigate | 1 | NavigateTaskDefinition / NpcNavigationRequest | Physical arrival |
| deliver | 2 | DeliveryTaskDefinition | Confirmed delivery from carried stock |
| transport | 1 | TransportTaskDefinition | Confirmed transfer from explicit sources |
| machine | 1 | MachineTaskDefinition | Feed/output contract for one real machine |
| fish | 1 | FishingTaskDefinition | Confirmed catches and bounded return |
| explore | 1 | ExplorerTaskDefinition | Finite visited/failed exploration report |
| attack | 2 | AttackTaskDefinition | Exact target defeat; target ended is distinct |
| defend | 1 | DefendTaskDefinition | Duty duration followed by return |
| attack_area | 1 | AreaAttackTaskDefinition | Confirmed defeat quota and return |
| patrol | 1 | PatrolTaskDefinition | Route rounds, dwell and return |
| inventory_work | 1 | InventoryTaskDefinition / SupplyStock / UnloadExcess / PickupNearby | Finite actual transfers, complete or incomplete |
| mine | 1 | MiningTaskDefinition / MiningWorkOrder | Delivered items, removed resource blocks or whole clearance |
| farm | 1 | FarmTaskDefinition / FarmWorkOrder | Delivered crop yield |
| plant_trees | 1 | PlantingTaskDefinition / PlantingWorkOrder | Complete species layouts |
| food | 1 | FoodTaskDefinition / FoodWorkOrder | Delivered food with retained ration |
| lumberjack | 2 | LumberjackTaskDefinition / WoodSelection | Delivered matching wood and optional gap replant contract |

Common validation: task duration 20..72000 ticks, attempts 1..8, backoff 1..200
ticks; attack duration is further capped at 2400. Positions are finite and use
the supported global envelope; work boxes are inclusive, with bounded exclusions.
Travel spheres, local observation diameters, distinct endpoints, method-specific
geometry, inventory totals, reserved stock and return allowances are separate
relations. Individual limits and defaults are emitted by the catalog/schema;
do not substitute the sample quantities or retry limits from the planning brief.

Variant coverage includes four mining methods and three counting bases; three
crop species and three farm modes; three tree species and two planting modes;
four food sources; three inventory work kinds; five patrol reactions; explicit
machine faces/ports; optional lumberjack supply sources and replant operation.
All collections are bounded and copied. Entity filters use explicit type/tag IDs;
wood accepts only the existing four presets and twelve supported literal items.

Amendments use TaskChanges, TaskAmendmentPreparation, TaskCombatReactions and
TaskLogistics. Quantity, recipients, sources, time, replacement, tactics, reaction
and logistics are structurally described; availability is family- and state-specific.
Machine/fish/explore accept only time/tactics/reaction. Inventory accepts the same
three kinds. Missions accept tactics/time plus an existing area-attack quota.
Attack also permits restricted replacement of its existing exact target.
No combat family accepts ordinary work reactions/logistics. Resource/counting
changes require an explicit new objective where the existing runtime supports it.

Task state/reason names are derived from the current enums; they are not newly
invented model error codes. Rich per-resource progress, action phase, event journal,
failure aggregation and legal observed world context remain L1 work.

Public gateways inspected: OperationSupervisionApi, TaskPublicOrders and its
combat/harvest/inventory adapters, TaskPublicAmendments, TaskSupervision and the
assignment/revision/expiry guards. Current authority remains a connected summoner
or operator, same dimension, loaded NPC within 256 blocks. Definition documents
contain no actor, NPC, task identity or trusted admission envelope.

Evidence: autonomy/run-20260920-llm/. Initial checks passed the catalog/unit suite,
30 variant documents plus 16 family documents and 276 independent schema checks.
The grouped campaign passed clean build, 388 units, 208 required native tests,
12 real client scenarios, both three-mod loading checks and distribution/boundary
guards. Frozen source hashes matched after execution; see l0-evidence.json.
L0.1–L0.4 and the remaining P11.1/P11.7 catalog requirements are complete.
