package io.samcnpc.behavior.task

import net.minecraft.nbt.*

internal object TaskLogisticsCodec {
    fun write(state: TaskLogisticsState) = CompoundTag().apply {
        put("policy", InventoryWorkCodec.writePolicy(state.policy)); putInt("cooldown", state.cooldownRemaining)
        put("outcomes", ListTag().apply { for (outcome in state.outcomes) add(writeOutcome(outcome)) })
    }
    fun read(tag: CompoundTag): TaskLogisticsState {
        InventoryWorkCodec.keys(tag, "policy", "cooldown", "outcomes")
        val policy = InventoryWorkCodec.readPolicy(InventoryWorkCodec.compound(tag, "policy"))
        val cooldown = InventoryWorkCodec.int(tag, "cooldown")
        require(cooldown in 0..6000) { "invalid inventory cooldown" }
        val outcomes = InventoryWorkCodec.list(tag, "outcomes", 32).map(::readOutcome)
        require(outcomes.map { it.frameId }.distinct().size == outcomes.size) { "duplicate inventory outcome frame" }
        return TaskLogisticsState(policy, cooldown, outcomes.toMutableList())
    }
    private fun writeOutcome(o: InventoryWorkOutcome) = CompoundTag().apply {
        putUUID("frameId", o.frameId); putString("kind", o.kind.name); putInt("revision", o.revision); putString("reason", o.reason.name)
        putBoolean("returned", o.returned); putInt("steps", o.steps); putString("detail", o.detail)
        put("goals", LumberjackTaskCodec.counts(o.goals)); put("supplied", LumberjackTaskCodec.counts(o.supplied))
        put("unloaded", LumberjackTaskCodec.counts(o.unloaded)); put("picked", LumberjackTaskCodec.counts(o.picked))
        put("sources",TaskObjectiveCodec.deliveries(o.sources)); put("recipients",TaskObjectiveCodec.deliveries(o.recipients))
    }
    private fun readOutcome(tag: CompoundTag): InventoryWorkOutcome {
        InventoryWorkCodec.keys(tag, "frameId", "kind", "revision", "reason", "returned", "steps", "detail", "goals", "supplied", "unloaded", "picked", "sources", "recipients")
        require(tag.hasUUID("frameId")) { "missing inventory outcome identity" }
        val kind = InventoryWorkKind.valueOf(tag.getString("kind")); val reason = InventoryWorkReason.valueOf(tag.getString("reason"))
        val revision = InventoryWorkCodec.int(tag, "revision"); val steps = InventoryWorkCodec.int(tag, "steps"); val detail = tag.getString("detail")
        require(revision in 0..32 && steps in 0..128 && detail.length <= 256) { "invalid inventory outcome limits" }
        val goals = InventoryStateCodec.counts(tag, "goals"); val supplied = InventoryStateCodec.counts(tag, "supplied")
        val unloaded = InventoryStateCodec.counts(tag, "unloaded"); val picked = InventoryStateCodec.counts(tag, "picked")
        require((supplied.isEmpty() || kind in setOf(InventoryWorkKind.SUPPLY, InventoryWorkKind.COLLECT, InventoryWorkKind.ENSURE)) && (unloaded.isEmpty() || kind == InventoryWorkKind.UNLOAD) &&
            (picked.isEmpty() || kind == InventoryWorkKind.PICKUP) && picked.values.sum() <= 256) { "inventory outcome kind/credit differs" }
        require((supplied.keys + unloaded.keys).all { (supplied[it] ?: unloaded[it] ?: 0) <= (goals[it] ?: 0) }) { "inventory outcome exceeds its captured quota" }
        val sources = InventoryTransferRows.read(tag,"sources"); val recipients = InventoryTransferRows.read(tag,"recipients")
        InventoryTransferRows.validate(sources,supplied); InventoryTransferRows.validate(recipients,unloaded)
        return InventoryWorkOutcome(tag.getUUID("frameId"), kind, revision, reason, InventoryWorkCodec.bool(tag, "returned"), goals, supplied, unloaded, picked, steps, detail, sources, recipients)
    }
}
