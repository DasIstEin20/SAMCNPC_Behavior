# ADR 0091: Bounded visual observations for external planners

Date: 2026-09-20. Status: accepted; canonical clean/unit/native/client verification passed.

General Core entity observations include combat relationship/damage facts and
NpcCombatRules.facts calls vanilla hasLineOfSight. They are useful to deterministic
mechanics, but must not be forwarded wholesale to an external planner. A clear
collision ray alone also does not exclude invisible or spectator entities.

Add an explicit Core visual query centered on the real NPC body/eye: radius
0.5..12 blocks, up to 64 loaded candidates and up to 16 visible results. Use the
vanilla Level.getEntities overload with an output limit, verified in the pinned
1.20.1 mapped JAR. Only LoadedNpcBlocks rays may check occlusion. Missing chunks
are never requested/generated. Results are nearest-first among the bounded
examined candidates, not a claim of globally nearest entities.

Exclude removed, invisible and spectator entities. Blindness returns an explicit
unavailable result rather than an empty scan. Copy only apparent type, identity,
position/velocity, alive/player flags and dropped-stack values. Do not include
another body's health, summoner binding, damage history or container contents.
An adapter without this capability returns UNSUPPORTED, never fake empty data.

This is a requested observation mechanism, with no automatic scanning or change
to existing combat selection. Core owns the physical sensor. Behavior will apply
the existing actor authorization and expose copied source/tick/unknown/truncation
metadata; LLM will receive only those values. Supplied block/fluid observations
must separately prove a bounded ray from the real eye before reading the block.

Validation: 412 units, 141 Core and 210 Behavior native cases, real client visual
inspection probes and both three-mod smokes; see visual-evidence.json.
