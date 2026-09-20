# L1 public context audit — 2026-09-20

L1.1 source audit complete. This document is not evidence that L1.2–L1.5 run yet.
L0 catalog and L3 transport are verified independently; neither exposes world handles.

| Context section | Existing source | Remaining projection |
|---|---|---|
| Identity | Core NpcHandle displayName; NpcSnapshot UUID/summoner/dimension | Bounded copied name, authoritative actor result; no caller-supplied identity |
| Body | NpcSnapshot position/eye/velocity/rotation/ground/water/lava/health fraction | On-demand absolute health/max/absorption and <=32 effects; keep costly details out of ordinary per-tick snapshot |
| Inventory | NpcFacade.inventoryContents, 36 NpcInventoryEntry values | All 36 including empties, <=16 enchantments per item, truncation; no raw NBT |
| Equipment | equipmentContents/equipmentKnowledge and NpcCombatItemFacts | Separate seven authoritative equipment/reserve stores; selected main hand aliases one inventory slot; explicit resource readiness |
| Task identity | OperationObservation, revisions/objective/frames | Reuse existing authority/revisions; do not grant authority through a context ID |
| Task definition | Closed TaskDefinition families and versioned public OperationOrder | Immutable reverse projection of current definition, including older supported persisted versions, without lossy defaults |
| Progress | TaskFrame resource/profession states | Family-specific measured counters and phase; unknown when state is not captured, never an invented zero or percentage |
| Action | NpcSnapshot blockBreak/itemUse/rangedAttack/fishing/navigation/recentCompletions | One bounded physical-action projection independent of task type; do not expose control handles |
| Events/failures | TaskRecord state/reason/failures, Core action completion/lifecycle events | Generic bounded Behavior journal/event interface with cleanup and unsubscribing; existing code does not retain a chronological failure journal |
| World | NpcWorldView bounded reads and visibleBlockFrom/visibleFrom | Require visibility and loaded chunks before reporting facts; source/tick/age; limits and explicit unavailable reasons |
| Resources | HarvestWorkClaims / SpatialWorkClaimKernel, ContainerStepReservations, resource ledgers | Expose only this NPC/task's active claims and measured reserves; do not expose neighbors' claims/stock |
| Policy/capabilities | Registered operation catalog, current Behavior validators | Intersect with request/mode policy; compact schema/context budget, not entire pretty catalog per call |
| Memory/clock | Current gameTime and durable Behavior revisions | LLM goal/memory later; transient server and NPC observation generations invalidate reload/unload/restart |

## Mechanical details

NpcRangedAttackController.findArrowAmmunition searches the ammunition reserve and
36 inventory slots. It does not count offhand arrows. Main hand is already one of
the 36 slots. Bow still requires an arrow with Infinity; a charged crossbow does
not require a fresh arrow. NpcCombatItemFacts already distinguishes these cases
and unsupported trident resource states. A resource-readiness fact is not proof
of target visibility, reach, permission or an available action channel.

NpcItemClassifier caches item-level roles and invalidates them after tag reload.
Its per-stack combat facts are separate. Reuse this classifier; do not ask the
model to infer item mechanics or claim unknown mod weapons are usable.

## Real progress, not generic success text

Delivery has ResourceProgress.delivered/retained/uncertain and explicit receipts.
ProducedCargoState reads delivered/available from per-resource accounting.
Mining has its own removed-resource vs delivered-item counting distinction.
Planting counts completed species footprints separately from individual placed
saplings. Fishing records casts/caught/collected catches and physical resources.
Explorer records visited nodes/rejected legs, not a fictional discovered ore map.
Combat records confirmed defeatedTargets, duty/waypoint/patrol state and healing use.
Profession phase/stop/reconciliation flags remain visible, including uncertainty.
Status strings from TaskService are diagnostics and must not be parsed as contracts.

## Legal sensors

Core's bounded block/container reads alone do not enforce visibility. In particular,
NpcEntityWorldView.observeContainer reads through an endpoint within observation
range; it is not proof that a planner may read arbitrary chest contents. L1 must
not forward raw nearby container inventories. It may describe a visible container
surface; private contents stay unavailable until a separate legitimate observation
and authorization contract supports them.

visibleBlockFrom uses LoadedNpcBlocks and a <=12-block eye ray; unavailable chunks
return unknown. For planner context use the NPC's actual feet, not hypothetical
standing positions. Entity queries also need visibility filtering. Do not scan an
ore cube or force chunks to load. User aliases/task target coordinates are intentions,
not sensor facts or permissions. Do not promote older task checkpoints to fresh facts.

## Authority and lifecycle

Reuse TaskSupervision.withNpc: authoritative server thread, current connected actor,
summoner/operator, same dimension, loaded NPC and <=256-block distance. Null context
on denial. Server-thread capture returns immutable copies; no NpcFacade, ItemStack,
Entity, Level, NBT or internal TaskRecord reaches LLM workers.

TaskService.observeTick and executeSelected observe terminal transitions; direct
assign/control/amend, assignmentsChanged, removed and clearTransient need matching
event/lifetime handling. Ordinary per-tick progress/retries must not trigger inference.
Record bounded data only on meaningful changes, and distinguish observation events
from scheduler decision boundaries. BehaviorRuntimeService.reload invalidates
runtime plans; expose a generation change rather than assuming catalog document
version alone captures reload. No LLM import or dependency enters Behavior.

Next complete slice: on-demand Core body/item details with immutable copies,
native tests and thread/stale-facade checks, followed by the authorized Behavior
projection, journals and ContextBuilder. Schemas remain data and inference remains
disabled until the admission gates are implemented.

## Sensor follow-up discovered during implementation

NpcEntityWorldView.visibleFrom checks a loaded-block collider ray but does not
check Entity.isInvisible or spectator status. Its existing NpcEntityObservation
has neither value. A planner sensor must not infer perceptibility from a clear
ray alone. Add copied mechanical visibility flags to the public Core observation
(with unknown for third-party adapters), then filter invisible/spectator entities
at the Behavior sensor boundary. Do not change ordinary combat policy as a side
effect. Include a real-world invisible/occluded entity regression.

For fluid blocks, visibleBlockFrom uses Fluid.NONE and an outline. A bounded
raycast from the actual NPC eye with includeFluids=true uses LoadedNpcBlocks and
can confirm the supplied fluid surface without force-loading chunks. Do not use
a hypothetical standing origin, raw container inventories, or unrestricted entity
combat metadata in the planner projection. Foreign damage/relationship histories
are unnecessary for a visible entity description.

NpcInventoryLoadSnapshot.generation identifies an NBT-load observation, not every
newly spawned body/session. It cannot alone stand in for a complete NPC lifecycle
or server/reload generation. Generation invalidation must cover spawn, unload,
reload and server stop explicitly.
