# On-demand operation events

OperationEventApi.subscribe is server-thread only and uses the same current
connected summoner/operator, dimension and 256-block checks as task supervision.
There are at most 128 subscriptions and four per NPC. Token state remains readable
after closure; unsubscribe is idempotent for a closed original token.

Only watched NPCs have a journal (32 entries). OperationInspection.journal is
NotRecorded otherwise. Recorded history gives its ID, coverage start, last sample,
sequence range and discarded-entry count. It is sampled at server tick end, not
a complete replay of every intermediate transition. Revision/failure jumps are
marked coalesced; a finished interruption is detected even if the final stack is
unchanged. Cursor-based callback batches contain at most the retained 32 entries.

Journal events include stable task/frame/reason/action codes and actual failure
counters. Core completions have no reliable Behavior task correlation: their event
leaves task/frame IDs unknown rather than assigning an old action to a new task.
Raw diagnostics, other NPC reservations, arbitrary world targets and invented
recovery-step names are excluded. No network, JSON, inventory projection or visual
scan occurs in the tick-end sampler. Ordinary healthy progress/countdowns do not
produce events. Only TASK_COMPLETED and TASK_FAILED are planner decision boundaries;
assignment, control, retries, cancelled work and finished interruptions are not.

Callbacks run on the server thread after bounded sampling. Authorization and body
generations are rechecked before each call. A listener can close another token,
unload a body or reload the registry; invalidated queued callbacks are skipped.
An Exception closes that listener and logs only token/type, without exception text.
Callbacks should enqueue inference, never block the server on HTTP.

Logout/authority loss, unload/death, successful reload, body-generation change and
server stop remove listeners. No retained entity/player/facade is used by the
subscription registry. The third-party callback itself must not retain world
objects or start world access on a worker. Re-subscription requires fresh authority.
Journals are transient and disappear when the final listener closes; persistence
of bounded planner history belongs to the LLM module.

Native, real-client and dedicated-stop validation passed: 430 units, 214 Behavior
Forge tests, 12 client cases (9 event probes), both three-mod loading smokes.
See PROJECT_STATE.md and the 749-source events campaign for exact evidence. LLM scheduling/admission is
separate work and is not enabled by subscribing to this API.
