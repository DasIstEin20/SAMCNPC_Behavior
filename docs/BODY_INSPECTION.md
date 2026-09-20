# On-demand own-body inspection

`NpcFacade.inspectBody()` captures one loaded, living NPC on the authoritative
server thread. The returned `NpcBodyInspection` contains only copied values and
can be serialized off-thread. Retaining a facade does not permit later reads
after removal or reads from a worker. An implementation that does not support
this optional capability returns null; callers must report unavailable data.

Ordinary per-tick `snapshot()` remains unchanged. This inspection performs no
world search, chunk loading, HTTP request, inventory change or autonomous action.

| Field | Meaning |
| --- | --- |
| observedTick | Capture tick, not a promise that a later decision is still valid |
| displayName | At most 128 characters; text is data, never authority |
| health / maxHealth / absorption | Actual body values |
| effects | Up to 32 effect IDs, remaining durations, amplifier and display flags |
| inventory | Exactly 36 real slot indices, including empty entries |
| mainHand | Alias of inventory[selectedHotbarSlot], not another stored stack |
| equipment | Offhand, four armor slots, ammunition and totem reserves |
| carriedArrowCount | Arrows in inventory plus ammunition reserve; excludes offhand |
| item stack | ID/count, maximum stack size, damage/max damage; no raw NBT |
| item knowledge | Existing Core role, tool, block, food and combat semantics |
| enchantments | Up to 16 entries per stack; explicit truncation and unreadable count |
| rangedReadiness | Resource status only, not permission, range or channel availability |

A bow requires an arrow even with Infinity. A charged crossbow needs no fresh
arrow. Trident limitations follow the existing combat classifier. Enchantment
IDs are bounded syntactically readable IDs; unknown mod IDs are not proof that
Core implements their effects.

Collections are defensively copied and unmodifiable, including nested role and
tool-kind sets. Changing equipment or effects after capture does not mutate an
earlier inspection. Callers must honor truncation rather than infer absence.

This Core capability does not authenticate an end user. Behavior's public context
adapter must authorize the current connected summoner/operator before capturing
and forwarding values. LLM workers must never receive the facade itself.

Implementation decision: [ADR 0089](adr/0089-on-demand-own-body-inspection.md).
Validation is recorded in PROJECT_STATE.md and the associated frozen-source
evidence; this document does not replace runtime evidence.
