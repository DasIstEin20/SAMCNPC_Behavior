package io.samcnpc.behavior.task

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

internal object TaskChangeCodec {
    fun write(change: TaskChange) = CompoundTag().apply {
        when (change) {
            is TaskChange.Quantity -> { putString("kind", "quantity"); putInt("amount", change.amount); putString("mode", change.mode.name) }
            is TaskChange.Redirect -> { putString("kind", "redirect"); put("choices", LumberjackSupplyCodec.writeChoices(change.recipients)) }
            is TaskChange.Sources -> { putString("kind", "sources"); put("choices", LumberjackSupplyCodec.writeChoices(change.sources)) }
            is TaskChange.Replace -> { putString("kind", "replace"); putString("objective", change.objective.name); put("definition", TaskCodec.writeDefinition(change.definition)) }
            is TaskChange.ExtendTime -> { putString("kind", "extendTime"); putInt("ticks", change.ticks) }
            is TaskChange.Reaction -> { putString("kind", "reaction"); put("policy", TaskReactionCodec.write(TaskReactionState(change.policy))) }
            is TaskChange.Logistics -> { putString("kind", "logistics"); put("policy", InventoryWorkCodec.writePolicy(change.policy)) }
            is TaskChange.Tactics -> { putString("kind", "tactics"); put("tactics", CombatTacticsCodec.write(change.tactics)) }
        }
    }
    fun read(tag: CompoundTag): TaskChange {
        val change = when (tag.getString("kind")) {
            "quantity" -> { keys(tag, "amount", "mode"); TaskChange.Quantity(integer(tag, "amount"), QuantityChangeMode.valueOf(tag.getString("mode"))) }
            "redirect" -> { keys(tag, "choices"); TaskChange.Redirect(LumberjackSupplyCodec.readChoices(compound(tag, "choices")) ?: throw IllegalArgumentException("redirect requires recipients")) }
            "sources" -> { keys(tag, "choices"); TaskChange.Sources(LumberjackSupplyCodec.readChoices(compound(tag, "choices"))) }
            "replace" -> { keys(tag, "objective", "definition"); TaskChange.Replace(TaskCodec.readDefinition(compound(tag, "definition")), ObjectiveChangeMode.valueOf(tag.getString("objective"))) }
            "extendTime" -> { keys(tag, "ticks"); TaskChange.ExtendTime(integer(tag, "ticks")) }
            "reaction" -> { keys(tag, "policy"); TaskChange.Reaction(TaskReactionCodec.read(compound(tag, "policy"), 5).policy) }
            "logistics" -> { keys(tag, "policy"); TaskChange.Logistics(InventoryWorkCodec.readPolicy(compound(tag, "policy"))) }
            "tactics" -> { keys(tag, "tactics"); TaskChange.Tactics(CombatTacticsCodec.read(compound(tag, "tactics"))) }
            else -> throw IllegalArgumentException("unknown amendment kind")
        }
        require(TaskChanges.validationProblem(change) == null) { "invalid bounded amendment parameters" }
        return change
    }
    private fun keys(tag: CompoundTag, vararg names: String) { require(tag.allKeys == setOf("kind") + names) { "unknown/missing amendment parameters" } }
    internal fun integer(tag: CompoundTag, key: String): Int { require(tag.contains(key, Tag.TAG_INT.toInt())) { "missing amendment integer $key" }; return tag.getInt(key) }
    internal fun compound(tag: CompoundTag, key: String): CompoundTag { require(tag.contains(key, Tag.TAG_COMPOUND.toInt())) { "missing amendment compound $key" }; return tag.getCompound(key) }
}
