# Development roadmap — local execution

F02 update 2026-09-20: [LLM_INTEGRATION_PLAN](LLM_INTEGRATION_PLAN.md) is IN_PROGRESS
with 8/36 implementation points (L0 and L1 audit complete; transport verified). Operation catalog → context/decisions/provider →
Translator → Supervisor → Planner. ADR 0085 supersedes primitive LLM control.
The dated progress snapshot below is historical; current evidence is in PROJECT_STATE.md.

Updated 2026-09-11. The active detailed sequence is
[AUTONOMOUS_DEVELOPMENT_PLAN.md](AUTONOMOUS_DEVELOPMENT_PLAN.md), maintained locally
for one Codex worker. **P00–P03 are complete; P04 is in progress; P05–P12 are not started.**
The user resumed autonomous implementation and explicitly requested heartbeat; it is armed.
The user expanded the required operations; the earlier primitive LLM proposal is
superseded by the high-level F02 plan above. For operation history,
see [OPERATIONS.md](OPERATIONS.md) and
[ADR 0024](adr/0024-predefined-operations-and-future-primitive-control.md).
The user also accepted [15 small behavior components](BEHAVIOR_COMPONENTS.md), now
mapped to P04/P06/P07/P08. [SIMILAR_MODS](SIMILAR_MODS.md) records public-source
inspirations to consult for those points; the mod comparison is not a quality benchmark.

| Phase | Outcome |
|---|---|
| P00 | Current baseline, acceptance mapping and trustworthy checks |
| P01 | Read-only conditions, typed compiled definitions and complete channel arbitration |
| P02 | Consistent action lifecycle and verified Core body mechanics |
| P03 | Bounded observations, progress detection and diagnostics |
| P04 | Durable tasks, cancellation and bounded recovery |
| P05 | Lumberjack with wood species, work area and delivered quantity |
| P06 | Follow, defend and attack operations with interruption/resumption |
| P07 | Transport, mining, food gathering, farming and tree planting, in tested slices |
| P08 | Deterministic multi-NPC coordination and shared-resource handling |
| P09 | Restart/reload/lifecycle hardening and measured performance |
| P10 | Core/Behavior maturity gate |
| P11 | External JSON format, definition catalog, migrations and authoring documentation |
| P12 | Full local acceptance and three-artifact verification |
| F01 | Deferred consideration of a custom-pack GUI creator |
| F02 | Planned LLM integration: Translator, Supervisor, Planner over Behavior operations |

Existing external loading and its tests remain maintained throughout. Built-ins keep
using the shared pack/compiler/runtime pipeline. The user deferred external-pack
finalization, not its final acceptance requirement; see
[ADR 0018](adr/0018-code-first-behavior-development.md).

The original bootstrap milestones below are historical context, not the current
implementation order. LLM provider implementation is tracked separately in the F02
plan; primitive control handover is excluded from that scope. Core provides body primitives; Behavior must execute the
complete operation catalog and local recovery without LLM. The current checklist has
23 completed and 89 remaining points (112 total). The earlier 55/81-point remainders
predate the successive operation/component extensions. Checklist counts do not estimate time.

## Historical reboot milestones

## M0 — build/repo

Three modules, Kotlin, Java 17, clean build, client + dedicated server runs.

## M1 — visible core NPC

Summon/dismiss/list/info, summoner UUID binding, persistence, player model, dynamic summoner skin, fallback skin.

## M2 — action surface

Movement/look/hands/inventory/melee/interactions with explicit result/state reporting.

## M3 — behavior v1

JSON parser/compiler, registries, rule evaluation, channel arbitration, reload/diagnostics, safe idle.

## M4 — default companion bundle

Idle/look + follow summoner + defend/retaliate, all implemented as behavior packs and registered primitives rather than Core policy.

## M5 — deeper player actions

Mining, placing, item use, ranged combat, tool selection, richer animations, pickup policies.

## M6 — hardening/tooling

Persistence migrations, multiplayer stress, GameTests, performance counters, debug inspector.

## M7 — LLM provider work

Only after M0–M6 foundations are clean. Provider translates intent into the existing Behavior surface; no privileged bypass.
