# Bounded inventory work (O2, implementation under verification)

The shared `samcnpc:inventory_work` operation executes explicit supply, protected unload
or nearby pickup and then returns to its captured starting position. It uses the same
physical Core transfer calls, fair container leases and approach/navigation machinery as
transport. Every transfer is credited from matching real inventory/container deltas.
A return is reported only after actual arrival; exhausted time or blocked return is a
partial failure, never a teleport or fabricated completion.

Manual commands (absolute container coordinates, quoted lists):

```text
/samcnpc behavior task assign <npc> supply "x,y,z;x,y,z" "minecraft:bread=2/16;minecraft:torch=8/32/4"
/samcnpc behavior task assign <npc> unload "x,y,z" "minecraft:cobblestone=16;minecraft:dirt=8"
/samcnpc behavior task assign <npc> pickup "minecraft:iron_ingot;minecraft:coal" 4 32
```

Supply entries mean `item=minimum/target[/sourceReserve]`; unload entries mean
`item=retainedReserve`. Defaults: fixed16-block travel radius,600 ticks of work,
1200 ticks overall including return,128 physical action attempts. Item lists are1..16
unique IDs; explicit source/recipient alternatives are1..8. The selected main-hand slot
and every separately equipped store are protected from optional unloading. Reserve
counts include all real carried/equipped stores once; main hand aliases its inventory slot.

To enable the same executor as interruptions of ordinary work:

```text
/samcnpc behavior task logistics <npc> supply "x,y,z" "minecraft:bread=2/16"
/samcnpc behavior task logistics <npc> unload "x,y,z" "minecraft:cobblestone=16"
/samcnpc behavior task logistics <npc> pickup "minecraft:iron_ingot" 4 32
/samcnpc behavior task logistics <npc> limits 32 600 1200 200
/samcnpc behavior task logistics <npc> off
```

The four limit arguments are travel radius, work ticks, total ticks and cooldown ticks.
These controls submit revisioned amendments through the same summoner/operator checks,
request identity, replay protection, expiry and safe-boundary rules as other task edits.
The first enabled policy fixes its travel anchor at the NPC's current position. An explicit
policy authorizes travel to its listed containers within that boundary; the primary work
area remains unchanged. Switching policy waits until the current interruption returns.
Each detour captures its own return position and the amendment revision that began it.
Structural primary changes wait for its return; a deliberate parent time extension never
refreshes the child deadline or rewrites the child's original revision.

Supplies trigger below the minimum and fill toward the target. A simultaneous unload
reserve for the same item must be at least its supply target, preventing stock cycling.
Unload runs first when it can free space, then supplies, then optional pickup. Each detour
captures finite quantities; fresh incidental gains cannot indefinitely expand unloading.
The original task/frame identity, time and attempts remain; active time includes detours,
backoff and combat. Existing depth3 and lifetime32-interruption limits still apply.

All of the active primary's cargo/harvest output is protected from optional unload,
including excess until that primary ends. Auxiliary supplies of the primary cargo item
remain protected stock and do not count as source-contract withdrawals. Wood supplies
are supplied, not gathered; unrelated unloading records its actual recipient without
advancing the wood quota. Explicitly enabling logistics on published v1 delivery/wood
uses the existing validated v2 checkpoint migration at a safe boundary.

Optional pickup has a1..8-block departure radius and1..256 deliberate item limit. A whole
observed drop stack larger than the remaining limit is skipped; Core may still perform
a smaller real insertion when its inventory fills. Exact entity/inventory deltas confirm
that actual amount. Active neighboring harvest claims protect both deliberate collection
and native contact pickup through the batched Core permission check (ADR 0043).
A matching physical pickup during an active pickup detour earns one receipt even when
Core collects it during approach. It must satisfy the same item, departure and remaining
stack limit. Other native contact pickups remain separate stock observations; these
policy limits apply to the detour, not to the world's incidental contact effects.

Strict optional v5 state stores captured quotas, item conservation, elapsed work,
endpoint identities and bounded outcome history. No path, entity reference or Core
execution ID is saved. The single inventory executor serves manual tasks and automatic
interruptions; the LLM module receives no special world mutation path.

Each outcome also records exact item subtotals for each real source/recipient.
Inspect them with `/samcnpc behavior task inventory_history <npc> [outcome] [page]`.
The codec checks these rows against the physical supplied/unloaded counters.

The frozen Z5 campaign j passed the full Behavior suite, physical inventory client case
and separate-JVM checkpoints. Exact receipt credit during passive approach is covered by
ADR 0073. See [VALIDATION.md](VALIDATION.md); the full mixed hour is a separate gate.
