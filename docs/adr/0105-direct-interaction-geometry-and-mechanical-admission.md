# 0105: Validate direct interaction geometry and mechanical admission

Status: accepted, 2026-09-23. Audit A4/A5/A6.

Direct public item/block interactions must check loaded targets, eye reach and
the actual outline hit before invoking foreign code. A placement wrapper's checks
cannot authorize a call to the lower-level API. Use the existing loaded-only block
view for bounded raycasts; a 0.02-block hit tolerance accommodates the existing
small inward offsets without accepting a different face or forged inside flag.
Native BlockItem placement retains its collision/event checks and no fake Player
is introduced. A supplied block-only interaction selects a visible outline face,
without choosing a target or policy.

One mechanical admission table covers persistent mining, ranged, fishing and held
use against new hand actions. Locomotion/look remain compatible. Continuation and
explicit cancellation belong to their current controllers; Core never chooses a
priority or replaces one action with another. Inventory changes retain the existing
stack identity invalidation behavior, and passive pickup is not a hand action.

Once a foreign item callback has been entered, an exception can follow a mutation.
Report FAILED/EFFECT_UNCERTAIN, retain the real world/item state and log the callback
failure; never claim unsupported/no effect, replay it, or roll back arbitrary world
state. Known unsupported paths may still reject before callback entry. Consumers
must stop/reconcile after uncertainty. Add focused native mutation-then-throw and
no-blind-retry tests alongside the direct geometry/conflict regressions.

Forge 47.4.21 `ForgeHooks.onPlaceItemIntoWorld` sets the world-global capture flag
without a finally. Refuse entry when capture/restore or pre-existing snapshots are
active. On RuntimeException, clear only this call's capture bookkeeping and publish
a bounded Core completion receipt with EFFECT_UNCERTAIN. Do not restore captured
blocks, replay notifications or infer how much the foreign call accomplished.
This is containment of the known foreign callback boundary, not a catch-all around
the task engine. VM errors remain errors. The test-only registered items mutate
the real block and stack, then throw NPE, ClassCastException or IllegalStateException.

Behavior's scaffold kernel persists a distinct PILLAR_EFFECT_UNCERTAIN result and
refuses retry, retargeting and automatic cleanup after it, including resumed state.
The lumberjack executor finishes while preserving the existing residue report;
its task guard also exposes the uncertainty as a terminal reconciliation problem.
Planting, farm soil/seed and berry consumers already stop on non-success; they do
not enter an automatic retry of the foreign interaction. Existing successful
placement/accounting and normal geometry recovery remain unchanged.
