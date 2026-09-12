# Physical fishing and finite fishing tasks

Core exposes explicit fishing mechanics. Behavior supplies the pond, safe stance, catch
quota and deadline. The first task uses a carried vanilla fishing rod; Core itself admits
items with Forge's FISHING_ROD_CAST tool action. No fake player, command execution or
inventory recipe substitution is involved.

## Task command

```
/samcnpc behavior task assign <npc> fish <waterX> <waterY> <waterZ> <catches> [<standX> <standY> <standZ> [<durationTicks> <pickupWaitTicks>]]
```

Select a water block, not the air above it. Catches range from 1 to 64 and count confirmed
fishing results, not a guaranteed fish species or item count. Vanilla fishing loot can
include fish, junk or treasure. With no stance argument the NPC fishes from its current
position. A supplied stance must be safe, dry, supported and within nine blocks of the
water. The command anchors the task and return position at the NPC's assignment position;
all work remains within 32 blocks. Defaults are 6000 total ticks and 240 pickup ticks.

The common `task status`, `pause`, `resume` and `cancel` controls apply. The task equips
its carried rod, casts once, renews its lease, reels on observed biting, collects actual
spawned items and returns after the quota. It requires a free carried slot before a new
cast. A broken rod, inaccessible loot or drained pond produces a finite diagnostic;
confirmed partial results remain in the world/inventory. There is no automatic crafting,
rod supply, selected-fish guarantee or pond search in this operation.

## Mechanism and arbitration

`NpcFacade.castFishing(NpcFishingCast)` accepts one hook. `fishingState()` exposes its
identity, hand, phase, position, elapsed time and remaining lease. `continueFishing(id)`
renews 40 ticks; absolute lifetime is 7200 ticks. `reelFishing(id)` consumes that cast and
returns an action result plus immutable snapshots of actual accepted item spawns.
`cancelFishing()` removes the transient hook without issuing fishing loot. Cast reach is
12 blocks and the live tether is limited to 32 blocks. Water and loaded geometry are
reobserved; a removed hook is not advertised by a fresh observation.

Both hands render in a real client, but the built-in task uses the main hand. Fishing
reserves its hand mechanics; Behavior also arbitrates look, movement, combat and inventory
for the task. A losing producer releases its own hook before combat runs and cannot reel
or renew a newer producer's cast. Core retains no fishing policy or automatic reeling.

Actual vanilla fishing loot tables and open-water predicates determine drops. Rod wear
and experience use native game objects. `NpcFishingLootCheckEvent` permits a monotonic
server-thread veto. Forge's Player-only fishing event, player advancements and arbitrary
fishing-mod player hooks are not emulated. No compatibility with those hooks is claimed.

## Persistence and limits

Task file version 8 retains the fishing state introduced in version 7. It stores fixed endpoints, original remaining budget, cast/catch counts,
actual spawn receipts and a bounded collection obligation. Hook entities, action UUIDs,
paths and controller leases are transient. Pause or combat cancels the hook; resuming
an unfinished waiting phase starts a fresh bounded cast under the original task. A saved
collection phase resumes pickup of the already created drops rather than another payout.

The executor sets an unconfirmed-payout marker before entering the Core callback. If a
save occurs inside that callback, loading rejects that task to safe idle and retains its
original NBT for inspection. It never repeats an uncertain payout. Confirmed collection
uses observed gross inventory pickup, preserving pre-existing stock. Another actor's
identical item can satisfy that aggregate observation; this is not exclusive item provenance
or a globally atomic transaction across Minecraft's independently saved world files.

## Verification

The native fishing cases, both-hand real-client rendering and separate-JVM waiting/collection
checkpoints passed. Current shared validation is recorded in [VALIDATION.md](VALIDATION.md).
