# ADR 0093: Observation lifetimes and on-demand Behavior events

Date: 2026-09-20. Status: accepted; generations and on-demand events verified.

Late external decisions need more than a game tick or inventory-load UUID.
An authorized inspection should include independent server-session, successful
registry-reload and body-generation IDs. Body removal/unload invalidates its ID;
a changed load observation or backward captured clock must invalidate it too.
Keep these values transient, bounded and server-thread owned. Do not change
task persistence merely to hold observation lifetimes.

Provide bounded on-demand subscriptions for current connected summoners/operators.
A token has an explicit active/closed state and close reason, and does not retain
an Entity, Level, ServerPlayer or facade. Callback records are removed on close,
authority loss, unload, successful reload and server stop. No invalidated callback
runs later. Consumers can observe closure and request a new authorized subscription.

Only watched NPCs need an event journal: limits 128 subscriptions,
four per NPC, 32 retained entries per journal and bounded delivery batches.
An ordinary non-subscribed inspection reports that history was not recorded;
it must not pretend an empty journal proves an absence of earlier failures.

A server-tick-end sampler can coalesce meaningful task ID/revision/state/frame/
failure-count changes and actual bounded Core completion failures. It must not
serialize JSON, scan the world, compute full context or call inference per tick.
Exclude routine progress/wait countdowns from notifications. Distinguish journal
events from planner decision boundaries: task completion/terminal failure can
trigger reevaluation; normal assignment, retry and completed interruption do not.

Deliver copied batches after sampling, with current actor authorization and a
bounded queue. Callback mutations are observed by the next sample, avoiding
recursive dispatch. Recheck token membership before each callback so close during
delivery prevents later calls. A callback exception closes only that listener and
logs a stable diagnostic without arbitrary exception text/secrets.

Events preserve actual machine-readable reason/action codes, task/frame IDs,
revisions, attempt counters and observed tick. Never invent unavailable recovery
step names or a precise failed target from an unrelated task destination. Mark
coalesced/lost history and unknown fields explicitly. Targeted tests must cover
multiple changes in one tick, journal bounds, rejected subscriptions, callback
close/failure, unload/reload/stop, stale actors and generation changes.
