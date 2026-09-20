# Bounded visual observations

Core exposes explicit real-eye sensors through NpcWorldView:

- observeVisibleEntities(NpcVisualEntityQuery): radius 0.5–12 blocks, at most
  64 candidate entities examined and 1–16 copied results.
- observeVisibleBlock(NpcBlockPosition): one supplied surface within 12 blocks,
  confirmed by a center-directed ray from the body's actual eye, including fluid surfaces.
  This conservative read is not an exhaustive visibility test for every shape.

These calls run on the authoritative server thread and reject retained views
after body removal. They never choose an action or run automatically each tick.
They exclude invisible/spectator/removed entities. Blindness produces BLINDED,
and an adapter without the capability produces UNSUPPORTED. An occluded,
out-of-range, air-only or unloaded supplied cell produces NOT_OBSERVED without
revealing its block identity.

Occlusion uses LoadedNpcBlocks. Missing chunks are never loaded by a ray.
An entity result is marked truncated if the candidate/result cap was reached,
any chunk in the requested envelope is unavailable, or a required ray encountered
unavailable data. Empty + truncated is not evidence that the area is empty.
Results are nearest-first among the bounded examined candidates, not globally
nearest. This is a bounded surrounding visual survey; it makes no directional
field-of-view promise.

Copied entity facts include type/UUID, position/velocity, alive/player flags and
a dropped item's ID/count/durability. Other entities' health, equipment, summoner
binding, damage history and private inventories are absent. Visible blocks expose
their physical type/environment and whether a container surface is present;
container contents are never read for this sensor.

Behavior exposes the sensors through:
OperationInspectionApi.inspect(server, actor, npcUuid, OperationWorldRequest(...)).
Authorization is identical to body/task inspection. The optional request can ask
for an entity scan and up to sixteen distinct supplied block cells. The ordinary
three-argument inspection performs no scan and returns world = null. Null sensors
mean not requested; unavailable results and truncation retain their own meanings.

World results carry dimension, capture tick and NPC_VISUAL_SENSOR provenance.
Supplied coordinates are requests, not proof of a current world fact. Consumers
compute age from their current game clock, account for lifecycle generations and
reobserve stale facts before a new effect. No observation authorizes an operation.

Decision: ADR 0091. Exact test evidence is recorded in PROJECT_STATE.md.

The separate opt-in `observeVisibleStock` sensor can count one specified item in a
reachable visible vanilla chest. It does not change visual block/entity projections
or enable an implicit inventory scan. See [Stock observation](STOCK_OBSERVATION.md).
