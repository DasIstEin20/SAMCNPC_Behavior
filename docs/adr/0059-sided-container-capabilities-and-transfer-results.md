# ADR 0059 — Sided container capabilities and bounded transfer accounting

Status: accepted for Z2 implementation, 2026-09-12. Implementation/evidence is tracked
separately in PROJECT_STATE.md; this ADR does not claim every transfer case is complete.

Core supplies a typed dimension/position/face endpoint, immutable bounded observations
and explicit transfer primitives. Behavior owns input/output choice, waiting, quotas,
reserves and machine workflow. No provider classes or mutable Forge objects cross the API.
A face has its own slot indexes; callers cannot reuse indexes from a different face.
Legacy vanilla container calls retain their meaning while both surfaces will share the
actual transfer mechanics. Combined chests retain their lid/occupant and neighbor rules.

The pinned Forge 1.20.1-47.4.21 sources provide IItemHandler, InvWrapper and SidedInvWrapper.
Reads cannot mutate the returned stack. Inserts report an actual remainder; extracts
report an actual removed stack. Simulation is not a capacity reservation. Never bypass
an unavailable sided capability by using a less restricted Container. Resolve each
provider on the server thread and verify its current identity/optional before a mutation.
No cached handles or persistent world references are introduced.

References: local forge-1.20.1-47.4.21-sources.jar interfaces/wrappers and
https://docs.minecraftforge.net/en/1.20.x/datastorage/capabilities/ .

Transfer results must account for actual quantities and preserve resources under partial
acceptance and supported callbacks. Reentrant operations are rejected; uncertain foreign
outcomes or mixed saves stop with explicit diagnostic/reconciliation, never blind replay
or overwriting another actor's changes. Tests must cover these boundaries before Z2.2 DONE.
Atomicity across unrelated mod block/entity saves or JVM crashes is not promised. Core entity data version 5 adds one bounded unresolved-transfer journal; v1-v4 migrate
with no journal. Saving inside a foreign call preserves a fence, and loading it blocks
further container exchanges. An insert removes offered units before calling foreign code;
only a confirmed remainder is restored. A thrown/invalid foreign result never causes an
optimistic rollback. The journal is diagnostic and not spendable inventory. Known returned
stacks that cannot safely be restored remain quarantined in that journal. There is no
automatic override or reissue; resolving a broken external handler/mixed save requires
inspection of actual stores. This is conservative failure handling, not crash-atomicity.
The public facade rejects recursive mutations while a transfer executes. Direct mutation
through unrelated mods' internal entity code is outside this contract and causes explicit
uncertainty when detected, rather than overwriting their changes.

An inventory capability does not expose arbitrary recipes, GUI state or every energy
system. Unknown facts remain unknown; deadlines bound waits. Machines process their own
resources; Core performs no crafting/item conversion. A real mod test is required for its
compatibility claim, independently of test providers and vanilla furnace evidence.

Native regression found the respawn queue still accepted only body v4. It now accepts v4-v5 using the common current version, retains unresolved transfer journals irrespective of keep-inventory, and preserves invalid/future journal NBT verbatim for inspection. Existing v4 queue fixtures and the actual eight-option death matrix remain required.
