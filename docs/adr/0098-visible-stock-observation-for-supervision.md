# ADR 0098: Visible stock observation for supervision

Date: 2026-09-20
Status: Accepted

Supervisor must observe actual stored stock; delivery accounting is not an inventory
sensor. Add an opt-in Core mechanical read and an authorized Behavior projection.
No new operation, body control, model command or unbounded scan is introduced.

The first sensor accepts one supplied item and one visible, reachable vanilla chest
(normal/trapped; single/double). It checks real-eye visibility, a 4.5-block radius,
already loaded chunks, vanilla lid rules and each half's lock/loot state. It returns
only item count, slot count, position and observation tick. Unknown is never zero.
Other containers are explicitly unsupported until their read semantics are verified.

Vanilla RandomizableContainerBlockEntity.getItem() calls unpackLootTable(), so the
existing general container observation cannot prove a mutation-free stock read.
Pinned Forge 47.4.21 public API inspection showed no public loot-table/lock getter.
Use vanilla saveWithoutMetadata() on at most two exact vanilla chest instances,
then inspect only Lock/LootTable and the at-most-27 slot entries per half. This
preserves ungenerated loot and avoids reflection/access transformers/fake players.
Raw NBT is transient in Core and never crosses the API or enters LLM context.
Serialization cost depends on existing item tags; do not use this sensor per tick.
Supervisor must stagger explicit bounded reads and measure their cost in L7.

A double chest must pass checks for both halves. Saved item IDs/counts are read as
metadata; nested item tags are neither interpreted nor sent to consumers. Locked
or loot-backed chests are unavailable even if a player might possess a key.
Registered third-party handlers are not invoked by this sensor.

Verification must prove single/double counts, immutable results after stock removal,
locked/loot/lid/hidden/out-of-range rejection, missing chunks remaining unloaded,
and server-thread/lifetime guards. Core must remain independent of Behavior/LLM.
The Behavior adapter must repeat connected summoner/operator/dimension/range checks.

Evidence: stock-evidence.json / STOCK_VALIDATION.json. 146 Core and 215 Behavior
native cases, 493 units, Core animation client and five stock reads after physical
deliveries on each of dedicated/client paths PASS. Clean build, boundaries, three
JARs and 816 source/build hashes PASS. Initial failures were fixture visibility and
vanilla retained loot metadata; assertions and production restrictions were retained.
