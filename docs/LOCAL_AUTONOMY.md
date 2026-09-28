# Deterministic local preparation and recovery

Deterministic preparation lives in Behavior and uses
Core's inventory, equipment knowledge, visibility, navigation and physical transfer
APIs. LLM is optional and never participates in these decisions.

## One item query

`minecraft:coal` means exactly coal: charcoal never satisfies it. `@axe` selects the
AXE tool kind reported by Core. An explicit union such as
`minecraft:coal|minecraft:charcoal` allows either. Queries contain one exact ID or
role, or 2..8 distinct non-nested alternatives; at most 512 characters. Exact IDs
are bounded to 128 characters. There are no tag queries until a suitable published
authoritative tag observation exists.

Supported roles: `@axe`, `@pickaxe`, `@shovel`, `@hoe`, `@food`,
`@placeable_block`, `@tool`, `@shield`, `@armor`, `@melee_weapon`,
`@ranged_weapon`, `@ammunition`. Roles come from Core item knowledge, not item-name
guesses or a model. Definition parsing happens at admission/load, never each tick.

Inventory totals count the 36 carried slots and separate equipment once. Main hand
aliases the selected hotbar slot. Fully broken tools do not satisfy usable-item
queries. Remaining durability is `(maxDamage - damage) / maxDamage`; nondamageable
items have fraction 1. Empty equipment never satisfies a durability condition.

## Registered components

The catalog now has 25 conditions and 25 actions. These eleven conditions and one
action were added; every ID below has the `samcnpc:` prefix:

| ID | Parameters / meaning |
| --- | --- |
| `inventory_count` | query, operator, count 0..4096, optional minimumDurability 0..1 |
| `inventory_free_slots` | operator, count 0..36; empty slots, not partial-stack capacity |
| `equipment_matches` | query, destination, optional minimumDurability |
| `durability_fraction` | destination, operator, value 0..1 |
| `at_position` | x, y, z, radius 0..64; three-dimensional feet distance |
| `task_status` | current durable task status; absent task is false |
| `task_attempts_remaining` | operator, count 0..8 in the current frame |
| `last_task_failure` | recorded current frame/task reason |
| `container_observed` | SOURCE or DESTINATION, index 0..7 |
| `container_count` | endpoint, index, query, operator, count |
| `container_free_slots` | endpoint, index, operator, count |
| `ensure_equipment` (action) | query, destination, optional minimumDurability |

Operators are `gt`, `gte`, `lt`, `lte`, `eq`. Equipment destinations are MAIN_HAND,
OFF_HAND, HEAD, CHEST, LEGS, FEET. The action claims inventory, main_hand and off_hand
together. It keeps already suitable equipment; otherwise it selects a physically
compatible carried candidate deterministically, using equipment/combat quality,
remaining durability and stable slot ties. Core retains block-specific mining-tool
selection. This action does not acquire items from a chest or create a task.

`BehaviorCatalogApi` / `BehaviorSchemaApi` and
`contracts/behavior-pack-registered.schema.json` are the authoritative field, enum,
bound, description and channel contract. Export with
`gradlew :samcnpc-behavior:exportBehaviorCatalog`. Catalog version is 2; behavior
document schema/semantics stay at 1.

## Authorized container observations

Only endpoints already declared by a current nonterminal task or its explicitly
authorized logistics policy are eligible. A loaded, nearby, visible, unlocked
vanilla chest must pass Core's existing stock-access check before Behavior reads
its bounded contents. No chunk generation, global scan or arbitrary coordinate
parameter is exposed to a rule. Observations are on demand, cached at most 20 ticks,
and invalidated when the endpoint contract changes. Transfers reobserve immediately.

Unknown is absent, not zero. Every numeric comparison on an unknown container is
false, including `count eq 0`. Use `container_observed` to distinguish a verified
empty chest. A distant endpoint can be approached by a durable task, but its stock
is not revealed remotely. Empty-slot counts do not promise NBT-compatible capacity.

## Durable operations and logistics

Inventory operation definition 3 adds `ENSURE`: query, count 1..64, optional ordered
source choices (up to eight), minimumDurability, optional equipment destination
(requires count 1), and sourceReserve. Already sufficient inventory finishes after
verification. Otherwise the existing bounded inventory frame approaches a permitted
source, observes, reserves a physical transfer, equips if requested, reobserves and
returns to its anchor. No source means carried inventory only. Source reserve is
kept across matching query items. Two NPCs share the existing transfer reservation
mechanism; receipts record only real transfers.

`UnloadExcess.minimumFreeSlots` (0..36, zero keeps legacy semantics) provides a
bounded space goal. Only explicitly listed excess may be unloaded. Selected tools,
equipped items, reserves, parent task resources and preparation-query items remain
protected. Failure to create enough space reports INVENTORY_FULL; no item is
silently dropped. An explicit logistics policy can run this recovery and ENSURE
preparation before resuming the parent task, with the existing cooldown, deadline,
action budget and at most 32 interruption receipts. This is the existing task
framework, not a second scheduler.

When an unload returns before required preparation is satisfied, selected parent
work waits through the existing logistics cooldown before attempting more work.
The original deadline continues; this does not grant new time or reset attempts.


Manual commands (replace coordinates and NPC identifier):

```text
/samcnpc behavior task assign Sam ensure "@axe" 1 "10,64,10" MAIN_HAND 0.2 0
/samcnpc behavior task assign Sam ensure "minecraft:coal" 2 "-" NONE 0 0
/samcnpc behavior task logistics Sam prepare "@axe" 1 "10,64,10" MAIN_HAND 0.2 0
/samcnpc behavior task logistics Sam free_slots "12,64,10" "minecraft:cobblestone=64" 2
/samcnpc behavior task inventory_history Sam 1
```

The first command creates a standalone preparation task. The policy commands amend
an existing task; they do not replace its primary goal. Keep the existing task's
controller pack assigned. `-` permits no external source, NONE requests no equipment
destination. Semicolon separates container positions or reserve entries. User
commands require the existing summoner/operator checks. There is no extra permission
grant from a JSON rule or a broad item query.

Missing usable tools/equipment/resources and unobservable sources produce explicit
MISSING_TOOL / MISSING_EQUIPMENT / MISSING_RESOURCE / SOURCE_UNAVAILABLE outcomes.
Low durability plus a carried backup, or an authorized replacement source, follows
the same ENSURE path. No crafting, purchase, global storage search or LLM fallback
is silently invented.

## Persistence and optional LLM compatibility

TaskStore format 11 preserves preparation policy, physical receipts and bounded
readiness captured at return. Older formats remain readable; they cannot smuggle
new fields or version-3 outcomes. Active work always reobserves after load. A
COMPLETED label alone is not a readiness proof. Public Behavior API is 6 and operation
catalog is 5. LLM consumes these published APIs; planner V1 remains default, V2 is
default-disabled, and trusted GoalConstraints / requiredReturnTo are unchanged.
Constrained planner inventory goals still admit only their supported exact Supply
contract; adding ENSURE does not implicitly authorize broader substitutions.
