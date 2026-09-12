# Bounded machine operations

Implemented in Core + Behavior on Forge 1.20.1. This is a deterministic registered task;
no LLM or recipe/crafting implementation is required. See [VALIDATION.md](VALIDATION.md) for the shared validation scope. Separate-JVM machine restart passed in the 20-live/one-dead campaign; Iron Furnaces compatibility also passed; the grouped Z5 campaign j also passed; the full mixed hour remains in progress.

## Manual command

```
/samcnpc behavior task assign <npc> machine <x y z> "<feeds>" "<output>"
/samcnpc behavior task assign <npc> machine <x y z> "<feeds>" "<output>" <pollTicks> <noProgressTicks> <durationTicks> <travelRadius>
```

Each port is `face,slot,item,quantity`. Separate up to four inputs with semicolons.
Faces are `up`, `down`, `north`, `south`, `east`, `west`, or `none` for the unsided
capability. Slot numbers belong to that exact face; they need not match GUI slots.
All ports address the one supplied block. The normal summoner/range command checks apply.

Example for a vanilla furnace, with four raw iron and one coal already carried:

```
/samcnpc behavior task assign Alex machine 120 64 -30 "up,0,minecraft:raw_iron,4;north,0,minecraft:coal,1" "down,0,minecraft:iron_ingot,4"
```

Defaults: observe every 20 ticks, stop after 1200 active work ticks without observed
progress, total original deadline 6000 ticks, travel radius 32 blocks. The command
returns the NPC to its original position. Limits: 1â€“4 feed ports, at most 2304 total
feed items and 2304 output items, one real transfer of at most 64 items per decision,
poll interval 5â€“200 ticks, travel radius 4â€“64 blocks. Inputs and output must have
different item identities. The caller supplies the ports and quantities; the task
does not discover recipes, select fuels or obtain missing input materials.

Use the normal `task status`, `pause`, `resume`, `cancel` and combat reaction controls.
Already confirmed feeds never reset. Changes to the fixed ports/quantities require
cancelling and assigning another contract using actual remaining inventories; bounded
time and combat policy controls retain the shared task semantics.

## Effects and persistence

Core resolves the actual capability for each call, including sided restrictions and
invalidation. Simulation predicts admissible capacity; only the actual returned result
creates transfer credit. A partial result advances only by the moved amount. Behavior
rotates input/output attempts and waits with bounded polling. It never converts items;
the real machine owns processing, fuel, energy and recipes. Collected output can include
pre-existing output of the requested item type, so the report means actual extraction,
not exclusive ownership of a recipe or newly manufactured material.

A full output or full NPC inventory can recover when the external blockage is removed.
Absent progress ends explicitly. No energy/recipe diagnosis is invented when the
interface exposes only inventory. A missing endpoint, changed block/slot shape or
uncertain Core transfer ends with confirmed partial facts intact. Same-type replacement
uses a fresh capability and cannot replay old feed counters.

Task-store version 8 retains the machine contract introduced in version 6. It persists the bounded machine contract, transferred counts and
remaining clocks. Versions 1â€“5 retain their migrations. Paths, capabilities, block entity
references and transient observations are never restored. A loaded inventory mismatch
fences the task; it is not compensated or hidden by reclassifying it as new pickup.
Core's pending foreign-transfer journal also fences automatic retry after uncertain
callbacks/save boundaries. This does not provide atomic crash commits across arbitrary
third-party chunk saves; see [ADR 0059](adr/0059-sided-container-capabilities-and-transfer-results.md).

## Native evidence and test fixtures

Seven native cases cover vanilla smelting, absent fuel, foreign full output, full NPC
inventory, machine removal/native drops, pause and actual combat, and partial sided
capability calls. The capability test begins with two explicit fixture emeralds; only
the furnace cases claim actual smelting. 254 unit tests cover the current Behavior tree,
including receipt mismatch, immutable/bounded ports, clock behavior, codec and migrations.

The Iron Furnaces fixture pins 4.1.8 and Modrinth version `KHAcRQwi`. It configures all
six automation faces for input/output in fixture data, as a player would configure the
machine before automation. Core and Behavior contain no Iron Furnaces class imports or
processing branches. The optional dependency is enabled only with `-PmachineCompatibility=true`;
it is not bundled in the three SAMCNPC artifacts. The configured iron furnace passed actual processing and separate-JVM continuation;
see [VALIDATION.md](VALIDATION.md). Other machines
and versions are not claimed. The compatibility dependency remains local to dev runtime,
with no transitive publication into the LLM module.

Design: [ADR 0061](adr/0061-bounded-machine-operations.md). Modrinth Maven setup follows
[the official guide](https://support.modrinth.com/en/articles/8801191-modrinth-maven).
