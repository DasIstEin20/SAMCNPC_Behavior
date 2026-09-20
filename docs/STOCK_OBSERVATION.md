# Visible stock observation v1

Status: verified (2026-09-20).

Core exposes `NpcWorldView.observeVisibleStock(NpcStockQuery(position, itemId))`.
Construct the query once when accepting configuration or a command; item identity
is validated there. A retained world view enforces authoritative server thread and
live NPC lifetime on every read. The default implementation returns UNSUPPORTED.

Behavior exposes `OperationStockApi.inspect(server, actor, npcUuid, dimensionId, query)`.
It repeats the same connected summoner/operator, actor dimension and 256-block
authority checks as task supervision. It also rejects a target dimension different
from the NPC's. Authority rejection has `stock=null`. An authorized read can still
return `NpcStockRead.Unavailable`; never treat this as a count of zero.

The first supported surfaces are exact vanilla normal/trapped chests, single or
double. One supplied chest must be visible from the real NPC eye and within 4.5
blocks. No discovery scan, chunk loading or inventory handler invocation is added.
Vanilla lid checks and both halves' lock/loot checks apply. The observation contains
only tick, supplied position/item, count and 27/54 slots. It grants no insertion
permission, reservation or guarantee that a later transfer will succeed.

Unavailable reasons: UNSUPPORTED, NOT_OBSERVED, OUT_OF_REACH, UNLOADED, BLOCKED,
LOCKED, LOOT_UNGENERATED and INVALID_CONTENTS. Other modded containers remain
explicitly unsupported. Visible block geometry does not reveal hidden inventory.

Vanilla `getItem` can unpack a loot table. This sensor instead uses public vanilla
serialization of at most two exact chest entities, checks lock/loot metadata and
counts the specified item in the bounded saved slot list. It never calls `getItem`
or interprets nested item tags. Raw NBT is not returned, logged, stored by the
consumer or included in model context. Serialization cost depends on existing item
tags: this is an explicit sensor, not a per-tick query. Stagger Supervisor reads and
measure their cost; see ADR0098.

Native verification: 146 Core and 215 Behavior required tests passed. New cases
cover real single/double counts and stock changes, an inaccessible second half,
loot/lock preservation, occlusion, unsupported containers, reach, missing chunks,
off-thread/removed NPC rejection, strangers, disconnected actors, wrong dimension
and range. Pure query/result bounds pass in 49 Core units. Five physical stock reads after completed deliveries passed on both the dedicated
server and integrated client. The Core animation client, configuration client, clean
build (493 total units), boundary/distribution guards and all 816 frozen hashes pass.
This API alone is not the Supervisor runtime and does not advance L5 completion.
