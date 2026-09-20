# Observation generations

Authorized operation inspections carry three transient IDs:

- serverSession changes when Behavior starts a server session;
- registry changes after a successful registry activation/reload;
- body changes after removal/unload and reuse of an NPC UUID, a different
  dimension/load observation, or a backward captured game clock.

Ordinary forward ticks and task control/definition revisions do not change the
physical body ID. Task revisions, authority, context TTL and goal/policy revision
remain separate checks. Generation IDs do not authorize an action or prove that
an older block observation is still current.

The server-thread runtime stores at most 4096 observed body identities. Removal
releases the entry and server stop clears the registry. Before startup, after
stop, with a negative clock or when a new entry exceeds capacity, inspection
returns NOT_READY with an explicit lifecycle reason and no inspection.

IDs are not persisted and no Entity, Level, ServerPlayer or facade is retained
in the registry. Pure generation tests cover stability, reload, removal, capacity,
stop/restart and clock/load changes. Native/client evidence is recorded in
PROJECT_STATE.md. The later LLM admission validator must bind and compare these
IDs; the event/subscription API and late-response admission are still unfinished.
