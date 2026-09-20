# Public operation inspection

`OperationInspectionApi.inspect(server, actor, npcUuid)` captures read-only values
for a current connected summoner or operator. It uses the same authoritative
thread, loaded-NPC, same-dimension and <=256-block checks as task supervision.
Denied calls return no inspection. An unavailable Core own-body capability returns
UNSUPPORTED; ordinary Behavior execution and the existing supervision API remain
independent of this optional detailed read.

The version-1 inspection contains:

- physical state and bounded recent action completions from Core;
- copied own-body health/effects, all 36 inventory slots and separate equipment;
- the existing task observation, with IDs, revisions, budgets and frame ordering;
- actual definition parameters for each of at most three frames;
- measured progress, or explicit NotInitialized when no measurement exists;
- per-frame accounting and own work reservations ([Resource inspection](RESOURCE_INSPECTION.md)).

All capture happens on the server thread. Collections and nested semantic sets
are detached/unmodifiable. Action result text is limited to 256 characters and
recent completions to 16 entries. Definition records have at most 64 fields,
sequences at most 128 entries and strings at most 256 characters. The closed
family projections bound nesting; no JSON/NBT object or mutable runtime escapes.

Definition fields match the operation catalog and contain the effective values
already stored in the task. A snapshot retains its actual definitionVersion,
including v1 delivery/attack/lumberjack. It is not an assignment document.
In particular, a legacy delivery's missing anchor is null rather than a fabricated
coordinate. Current versions can be serialized and checked through the existing
strict operation decoder; inspection itself never dispatches an operation.

Progress values carry explicit units. Delivery and transport report confirmed
delivery and retained/cargo accounting, not current chest contents. Mining keeps
removed resources, delivered items and completed volumes separate. Planting keeps
completed species layouts separate from placed saplings. Fishing distinguishes
casts, catches and collected catches. An exact attack reports confirmed defeats
only when its matching terminal result is available; guard/patrol states do not
invent a cumulative kill ledger they never recorded.

Uncertainty and pending reconciliation remain visible. A counter is retained task
evidence, not a fresh observation of an old location. The ordinary three-argument
inspection performs no world scan. The overload accepting OperationWorldRequest
explicitly captures bounded visual facts after the same actor authorization;
see [Visual observations](VISUAL_OBSERVATIONS.md). It never exposes nearby
container inventories. Generic event/failure journals, lifecycle generations,
and the final LLM ContextBuilder remain separate unfinished L1 work.

Decision: [ADR 0090](adr/0090-authorized-operation-inspection.md).
Validation status and exact runtime evidence are recorded in PROJECT_STATE.md.
