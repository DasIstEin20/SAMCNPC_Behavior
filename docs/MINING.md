# Mining operation

Operation ID `samcnpc:mine`, current definition version 2. Version 1 documents and
persisted tasks remain supported with their original horizontal tunnel semantics.

## Commands

The root is `/samcnpc behavior task assign <npc> mine`. Only the NPC's summoner or an
operator may assign or amend a task. The ordinary task status/pause/resume/cancel controls
apply. ID lists use **quoted vanilla strings**, with comma-separated literal namespaced
IDs. A dash means no access-block removals. Tags, classes, URLs and commands are not IDs.

```text
mine exposed <min x y z> <max x y z> <recipient x y z> "resource:block,resource:block2" - "output:item,output:item2" delivered_items <quantity> [durationTicks] [returnToStart]
mine vein <min x y z> <max x y z> <recipient x y z> "resource:block" - "output:item" delivered_items <quantity> [durationTicks] [returnToStart]
mine excavation <min x y z> <max x y z> <recipient x y z> "resource:block" "access:block,access:block2" "output:item,output:item2" cleared_volume 1 [durationTicks] [returnToStart]
mine tunnel <origin x y z> <east|west|south|north> <width> <height> <length> <recipient x y z> "resource:block" "access:block" "output:item,output:item2" cleared_volume 1 [durationTicks] [returnToStart]
```

For example, with a nearby NPC and an existing recipient at0,64,4:

```text
/samcnpc behavior task assign Miner mine exposed 4 64 0 7 64 2 0 64 4 "minecraft:iron_ore,minecraft:deepslate_iron_ore" - "minecraft:raw_iron" delivered_items 8 6000 true
```

`delivered_items` counts real produced cargo received by authorized containers.
`removed_resource_blocks` counts only confirmed removal of selected resource blocks;
permitted access stone is separate. `cleared_volume` requires one complete supplied
geometry and its observed clearance, plus delivery of retained eligible output. Initial
stock and auxiliary supplies cannot become produced-output quota credit. The primary
delivery drains produced stock only. The normal Core mechanics choose a suitable carried
tool for the exact supplied block; optional task logistics may supply concrete tools.

Tunnel origin is its left floor cell when looking along the supplied direction. Width
is1..3, height2..4 and length1..32. Cross-sections are removed from top to bottom before
advancing forward. Excavation scans top-down, then stable Z/X order. Exposed/vein work
cannot authorize unrelated access removals. A vein starts exposed and expands only from
physically confirmed connected members. Work selections/removed journals are bounded;
unloaded or unknown facts cannot grant removal permission.

Typed operations and version 2 documents also accept `tunnel.stepDown=1`, which
lowers each forward slice by one block and requires height3..4. The default zero
retains a horizontal tunnel. The supplied work box must match the tunnel's enclosing
bounds, but only exact slice cells may be removed; supporting stair blocks and
ceiling cells outside those slices remain protected. The shorthand command above
remains horizontal. See ADR0117 for versioning and persistence invariants.

Current command defaults are6000 ticks,64-block fixed travel radius and return to the
starting position. Work/recipient/return endpoints must remain inside the validated local
boundary. The typed definition also supports exclusions and ordered/nearest authorized
recipient lists; final public JSON/catalog exposure is Z6/P11. Same-objective quantity and
recipient amendments retain credit. Changing work geometry, resource filter or counting
basis requires an explicit new objective; the old physical report is archived.

## Stops and observations

Current conservative policy refuses fluid exposure, falling blocks, unbreakable blocks,
containers and unauthorized removals. Unknown block/environment facts stop safely. It
selects a supported visible side stance and includes navigation arrival tolerance when
checking whether the target might support the NPC. Unusable arrived stances are reconsidered
with a bounded exclusion list. Actual Core action reach/visibility/tool/Forge checks remain
authoritative. Completed world effects and actual partial cargo remain recorded on failure.

Collection waits for real drops, permits actual native contact pickup, and shares fair
harvest reservations with wood workers. No block-to-item conversion is simulated. A source
supply is protected stock; delivered quota comes from actual eligible gains. Tool wear,
drops and XP remain Core/vanilla mechanisms. Full adverse/tool-loss/XP/client acceptance
must be read from O4 evidence, not inferred from compilation or this command description.

Collection travel uses the original task/navigation deadline. While the NPC approaches a
known drop, it preserves the short wait for absent loot; slow travel and fair passage do
not consume that wait. Initial inventory remains protected and only physically collected
ore can be delivered. Core pickup radius can be increased through Forge Config or
`/samcnpc pickupradius <2..8>`; the mining recovery also works at the default two blocks.
