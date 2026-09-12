# Bounded exploration

The deterministic `samcnpc:explore` task walks a small frontier, remembers confirmed
waypoints and returns to its original anchor. It does not mine, place blocks, teleport,
create extra chunk tickets or invoke an LLM. The first confirmed cell is the anchor.

Use the existing summoner-authorized task command:

```
/samcnpc behavior task assign <npc> explore <radius> <maxCells>
/samcnpc behavior task assign <npc> explore <radius> <maxCells> <cellStep> <verticalRange> <chunkBudget> <heading> <durationTicks>
```

For example, `explore 16 12` visits at most twelve reachable cells inside a radius of
sixteen blocks and returns. The NPC must start on a dry, supported standing position.
The ordinary `task status`, `pause`, `resume` and `cancel` commands apply. An explicit
bounded time extension retains visited nodes, failed attempts and existing return intent.
Changing expedition geometry requires ending that task and assigning another one.

| Parameter | Range | Default |
| --- | --- | --- |
| radius | 8..96 blocks horizontally | required |
| maxCells | 1..64, including anchor | required |
| cellStep | 4..8 blocks | 4 |
| verticalRange | 1..16 blocks above/below anchor | 12 |
| chunkBudget | 9..256 possible chunk columns | 64 |
| heading | 0 east, 1 north, 2 west, 3 south | 0 |
| durationTicks | 40..72000 through this command | 6000 |

The optional parameters must be supplied together. A large radius can need an explicit
larger chunk budget. The validator counts the fixed horizontal envelope plus Core's
one-chunk activity margin on every side, using floor division for negative coordinates.
It rejects an insufficient budget. This is a conservative expedition footprint bound,
not a request to load every enclosed chunk: Core retains its usual maximum nine active
chunks per NPC loader and its global loader cap. Other actors' tickets are independent.

Candidate direction order is stable, but fresh observations determine where the NPC can
actually go. At most five nearby standing heights are checked per admitted planning slice.
The NPC records a visit only after a grounded physical arrival. A failed leg first recovers
to the last confirmed waypoint; an exhausted branch follows its parent trail backwards.
It skips attempted directions rather than repeating an unreachable edge forever.

Route nodes stay one horizontal block inside the expedition's outer envelope to leave
room for native movement. Crossing the outer envelope through external displacement
fails the task. These are navigation limits, not an invisible wall against physics.
This version uses ordinary ground paths and dry supported stances; it does not claim
safe traversal of every modded hazard or free exploration of arbitrary vertical caves.

Exploration stops at its cell quota, an exhausted reachable frontier, or a conservative
return reserve of 200 ticks plus 100 per parent edge. Return consumes the original task
clock. A newly blocked return can exhaust the original retries or deadline; the final
report then says failure. A returned partial expedition reports its explicit stop reason.

Task store version 8 persists a bounded parent tree, attempted directions, pending target,
return state and original budgets. It never saves a native path/action handle. Earlier
supported task files migrate without refreshing time or item receipts. Invalid or future
records remain preserved for repair and idle safely. Pause freezes task time; combat
interruptions spend the original primary clock and retain the same visited trail.

Native evidence currently covers ten physical scenarios plus separate-JVM save/load of
both a partial leg and an active return. Separate dedicated KEEP/DROP runs verify native death more than forty blocks from the
origin, actual respawn, item conservation, cancelled task retention and rejected old handles.
See [VALIDATION.md](VALIDATION.md) for shared validation and remaining gates.
