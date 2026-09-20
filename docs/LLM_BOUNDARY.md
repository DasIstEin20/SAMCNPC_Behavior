# LLM integration boundary

Updated 2026-09-20 by user decision. Implementation plan:
[LLM_INTEGRATION_PLAN](LLM_INTEGRATION_PLAN.md), decision:
[ADR 0085](adr/0085-llm-high-level-planning.md).
The existing samcnpc-llm remains a disabled shell until implemented and verified.
Autonomous implementation started on 2026-09-20; no fourth mod is added.

## Responsibility

LLM selects what to achieve next. Behavior owns operation execution, arbitration,
bounded local recovery and task persistence. Core owns body mechanisms.
The only execution flow is:

`user goal -> bounded context -> provider -> strict decision validation -> authorized public Behavior API -> normal task runtime -> Core`

F02 now implements Translator, then Supervisor, then Planner. This supersedes the
primitive-input/temporary-channel-handover requirements of ADR 0024 and ADR 0039.
The model cannot request movement axes, look rotation, jumping, equipment slots,
block primitives or a lease of body channels, even after recovery is exhausted.
It may choose another known operation, a supported amendment, WAIT or ASK_USER.

## Public boundary

Use the same OperationSupervisionApi and operation validators as ordinary clients.
Known typed orders and amendments are data; they do not require a second executor.
The full operation parameter catalog passed L0/P11.1/P11.7 on 2026-09-20. Component
metadata does not substitute for operation metadata.

Decisions are CONTINUE, ASSIGN, AMEND, PAUSE, RESUME, CANCEL, WAIT and ASK_USER.
Only one step is admitted after each fresh observation. CONTINUE does not reset a
task or certify goal completion; WAIT delays LLM supervision, not the current task.
Receipts and measured world results determine actual effects.

Missing observations must be explicitly unknown, not invented. Expose the bounded,
read-only task/body/inventory/sensor facts through public Behavior APIs. LLM does
not receive NpcFacade or mutable world handles. New Behavior interfaces are generic
observations/events, not LLM services; dependency direction remains one-way.

## Authority, freshness and recovery

Build snapshots and admit decisions on the authoritative server thread.
Provider I/O, JSON handling and inference run off that thread on immutable values,
with finite queues/timeouts and no background world access.

The current API requires the actual connected summoner/operator, the same dimension
and a loaded NPC within 256 blocks. F02 does not add offline delegation or bypass
the range check. Existing tasks may continue when the actor is unavailable; new
admissions wait. Recheck authority, goal/policy/catalog/world generation and task
revisions after inference. Never renew an obsolete request's revisions to force it
through. Reconcile uncertain assignment/control outcomes before a new decision.
Queued amendments remain PENDING until their existing receipt confirms otherwise.

User stop/cancel, replacement goals/tasks, logout, unload, death, reload and restart
invalidate applicable requests. Do not automatically resume a task stopped by the
user. Ordinary Behavior recovery has priority and does not require a provider.
Escalate terminal failures or meaningful unmet needs, coalesce events, bound retries,
detect repeated failed decisions and stop inference at the goal/call budget.

## Context and memory

NpcLlmContext is versioned and bounded: identity, body, equipment, all 36 inventory
slots without raw NBT, task and interruptions, current action, budgets, events,
failure history, legal observations/resources, user policy, capabilities, authority,
goal/memory and clocks. Count a selected main-hand inventory stack only once.
World facts carry source/age/staleness. No global scans, hidden-resource knowledge
or chunk loading solely to construct a prompt.

SavedData contains bounded goals, plans, user location aliases, verified outcomes
and reconciliation state. It stores no secrets, HTTP bodies, unbounded chat history,
world objects or permanent claims about current terrain/container contents.
Plans are unexecuted suggestions until their next step is revalidated.

## Provider isolation

The existing LlmProvider interface will evolve into typed requests/responses;
OpenAiCompatibleProvider is the proposed transport implementation.
Server-owned configuration supplies endpoint, model, optional credentials and
finite limits. Disabled is normal; there is no network request on default startup.
LM Studio/Ollama are the primary local test targets; additional backend/model
profiles need their own evidence. The plan records the official transport sources
and keeps backend capability claims separate from tested integration results.

Local validation remains mandatory even for schema-constrained output.
No free-text execution fallback, arbitrary tools, commands, scripts, reflection,
model-provided URLs, permission changes or direct Core world mutations.
Candidate behavior documents, if added in a future separate scope, continue through
the exact disk-JSON parser/schema/semantic validation and allow-listed registries.

## Acceptance

Use the L0–L7 gates in LLM_INTEGRATION_PLAN. Test real model/backend combinations as
well as deterministic fault injection, physical operations, lifecycle and restart.
Core/Behavior remain useful with LLM absent, disabled, offline, slow or incompatible.
A healthy deterministic task continues when inference fails; an idle NPC waits safely.
Record unverified profiles explicitly. The human two-account skin test remains
MANUAL_PENDING and does not block autonomous implementation or closeout.
