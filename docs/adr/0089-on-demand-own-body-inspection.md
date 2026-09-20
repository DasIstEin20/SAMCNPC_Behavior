# ADR 0089: On-demand immutable body/item inspection

Date: 2026-09-20. Status: accepted and verified.

L1 needs effects/enchantments and semantic equipment facts without adding repeated
NBT/detail work to NpcSnapshot's per-tick path. Add NpcFacade.inspectBody() returning
an optional immutable NpcBodyInspection. The Core implementation captures it only
on explicit request; third-party facades default to null (capability unavailable).
ServerThreadNpcFacade applies its existing thread/current-body guard.

The result includes bounded name, actual health/max/absorption, at most 32 effects,
all 36 inventory slots, seven equipment/reserve stores, and selected main-hand alias.
Items reuse the existing classifier and expose at most 16 enchantments, copied
roles, stack values, resource readiness and explicit truncation/unreadable counts.
No ItemStack, effect instance, NBT, entity, world, mutable collection or action handle
escapes. No new persistent format or networking is added.

Arrow totals follow current Core shooting sources: the 36 inventory slots plus
ammunition reserve. Offhand arrows are not consumed by the current controller.
Infinity still needs an arrow; charged crossbow readiness and trident limitations
come from existing NpcCombatItemFacts. Resource readiness does not grant target,
reach, permission or channel access. Core still selects no autonomous goal.

The helper resides beside the entity and adds only a forwarding call to the entity.
It performs no world scan, chunk loading, inventory mutation or HTTP. Behavior will
consume this through an authorized context boundary; LLM will not receive a facade.

Required evidence: immutable/bounded unit data, real entity effects/enchantments,
main-hand alias and ammo accounting, snapshot independence, off-thread/stale-facade
rejection, clean build, Core native tests and appropriate client/server regression.

Verified 2026-09-20: clean build; 402 units (Core 46, Behavior 337, LLM 19);
137 required Core Forge GameTests; real Core animation client; three-mod client
and dedicated server with GUI/emulated HTTP; source/boundary/distribution gates.
All 708 frozen source/build files match body-source-manifest.json.
Evidence: autonomy/run-20260920-llm/body-evidence.json.
