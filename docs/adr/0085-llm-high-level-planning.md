# ADR 0085: LLM as the high-level NPC planner

Status: accepted design by user request, 2026-09-20; implementation not started.

## Context

The existing public Behavior API supplies sixteen typed operation families,
observation, assignment, amendments, controls, revisions and amendment receipts.
It deliberately does not expose executors. The full parameter catalog and some
context fields still need public APIs. Earlier ADR 0024/0039 proposed both operation
supervision and temporary primitive control. The user now requests an executive
planner above Behavior, with Translator first, then Supervisor and Planner.

## Decision

F02 consumes only known high-level Behavior operations through its normal authorized
validation/admission paths. The eight decisions are CONTINUE, ASSIGN, AMEND, PAUSE,
RESUME, CANCEL, WAIT and ASK_USER. There is no primitive-input mode or temporary LLM
channel handover in this scope. This supersedes only those LLM-control portions of
ADR 0024/0039; their operation/adaptation/resource contracts remain applicable.

The first dependency is completing the existing P11.1/P11.7 operation parameter
catalog. Then add public read-only context/events, strict decisions, an isolated
OpenAI-compatible provider, Translator, Supervisor and Planner. Exact work packages
and evidence gates are in [LLM_INTEGRATION_PLAN](../LLM_INTEGRATION_PLAN.md).

Context uses bounded legal observations, full 36-slot semantic inventory and explicit
unknown/stale data. It cannot create hidden knowledge or force chunk loading.
Behavior exposes general immutable observations/events without any dependency on LLM.
Current connected-player authority, dimension and 256-block limits remain in force.

Requests are event-driven, bounded and asynchronous only at the provider boundary.
The server creates context identity and checks freshness/authority again on return.
Ordinary Behavior recovery continues without inference. One plan step is admitted
at a time; model text never establishes successful world effects. Failure fingerprints,
goal budgets and user-stop precedence prevent endless failed decisions.

LLM persistence will be bounded, versioned SavedData for goals/plans/aliases, verified
outcomes and reconciliation. It is separate from task persistence and is not assumed
transactional with TaskStore. Restart ambiguity must not replay a world effect.
Concrete saved-format changes receive implementation migrations and validation.

## Consequences

The existing samcnpc-llm becomes useful without adding a fourth JAR, a new behavior
executor, engine hacks or direct body control. The prototype provider interface must
be deliberately evolved rather than duplicated. There is no universal backend/model
compatibility claim, nor a guarantee that a planner works while its summoner is
offline/out of range. Such delegation would require a separate explicit design.

Existing automated gameplay checks remain required. This planning change does not
mark any LLM implementation gate complete. The two-account manual skin test is
MANUAL_PENDING and cannot become an autonomous dependency.
