package io.samcnpc.behavior.task

import net.minecraft.nbt.*

internal object InventoryStateCodec {
    fun write(state: InventoryWorkState) = CompoundTag().apply {
        put("resources", InventoryLedgerCodec.write(state.resources)); put("goals", LumberjackTaskCodec.counts(state.goals))
        putInt("revision", state.revision); putInt("workRemaining", state.workRemaining); putString("phase", state.phase.name)
        putInt("steps", state.steps); putString("detail", state.detail); state.reason?.let { putString("reason", it.name) }
        put("picked", LumberjackTaskCodec.counts(state.picked))
        put("withdrawals",TaskObjectiveCodec.deliveries(state.withdrawals)); put("deliveries",TaskObjectiveCodec.deliveries(state.deliveries))
        put("checkpoints", ListTag().apply { for ((p, shape) in state.checkpoints) add(CompoundTag().apply {
            put("position", LumberjackTaskCodec.position(p)); putString("block", shape.blockId); putInt("size", shape.size)
        }) })
        state.selected?.let { put("selected", LumberjackTaskCodec.position(it)) }
        state.selectedItem?.let { putString("selectedItem", it) }
        state.lastProblem?.let { putString("lastProblem", it.name) }
        put("deferred", ListTag().apply { for (p in state.deferred) add(LumberjackTaskCodec.position(p)) })
        state.readiness?.let { observation -> put("readiness", CompoundTag().apply {
            putInt("matchingCount", observation.matchingCount); putBoolean("equipmentMatches", observation.equipmentMatches); putInt("freeSlots", observation.freeSlots)
        }) }
    }
    fun read(tag: CompoundTag, definition: InventoryTaskDefinition): InventoryWorkState {
        require(tag.allKeys == setOf("resources", "goals", "revision", "workRemaining", "phase", "steps", "detail", "picked", "checkpoints", "deferred", "withdrawals", "deliveries") +
            setOf("reason", "selected", "selectedItem", "lastProblem", "readiness").filter { tag.contains(it) }) { "unknown/missing inventory state" }
        val resources = InventoryLedgerCodec.read(InventoryWorkCodec.compound(tag, "resources"))
        val goals = counts(tag, "goals"); val picked = counts(tag, "picked")
        val work = definition.work
        require((work is CollectContainer || work is EnsureItems || goals.keys.all { it in work.itemIds }) &&
            goals.values.sumOf { it.toLong() } <= if (work is CollectContainer) work.maxItems else 2304) { "inventory goals exceed the captured work" }
        require(work is PickupNearby || picked.isEmpty()) { "container work contains deliberate pickup credit" }
        require(picked.keys.all { it in work.itemIds } && (work !is PickupNearby || picked.values.sum() <= work.maxItems)) { "pickup credit exceeds the explicit limit" }
        require(resources.entries.all { (id, value) ->
            (value.supplied == 0 || (work is SupplyStock || work is CollectContainer || work is EnsureItems) && id in goals) && (value.delivered == 0 || work is UnloadExcess && id in goals) &&
            value.consumed == 0 && (picked[id] ?: 0) <= value.gathered
        }) { "inventory ledger has effects outside the authorized work kind" }
        require(goals.all { (id, count) -> when (work) {
            is EnsureItems -> count == work.count && (resources.entries[id]?.supplied ?: 0) <= count
            is CollectContainer -> (resources.entries[id]?.supplied ?: 0) <= count
            is SupplyStock -> count <= work.needs.first { it.itemId == id }.target && (resources.entries[id]?.supplied ?: 0) <= count
            is UnloadExcess -> count <= (resources.entries[id]?.initial ?: 0) && (resources.entries[id]?.delivered ?: 0) <= count
            is PickupNearby -> false
        } }) { "inventory work exceeds its captured quota" }
        val phase = InventoryWorkPhase.valueOf(tag.getString("phase"))
        val reason = if (tag.contains("reason")) InventoryWorkReason.valueOf(tag.getString("reason")) else null
        val revision = InventoryWorkCodec.int(tag, "revision"); val remaining = InventoryWorkCodec.int(tag, "workRemaining")
        val steps = InventoryWorkCodec.int(tag, "steps"); val detail = tag.getString("detail")
        require(revision in 0..32 && remaining in 0..definition.workTicks && steps in 0..definition.maxSteps && detail.length <= 256) { "invalid inventory limits/report" }
        require((phase == InventoryWorkPhase.RETURN) == (reason != null)) { "inventory return lacks a bounded work outcome" }
        val state = InventoryWorkState(resources, goals, revision, remaining, phase, steps, reason, detail, picked.toMutableMap())
        if (tag.contains("readiness")) {
            require(definition.version >= 3 && phase == InventoryWorkPhase.RETURN && (work is EnsureItems || work is UnloadExcess && work.minimumFreeSlots > 0)) { "readiness outside supported physical return" }
            val value = InventoryWorkCodec.compound(tag, "readiness")
            InventoryWorkCodec.keys(value, "matchingCount", "equipmentMatches", "freeSlots")
            val matching = InventoryWorkCodec.int(value, "matchingCount"); val free = InventoryWorkCodec.int(value, "freeSlots")
            require(matching in 0..4096 && free in 0..36 && value.contains("equipmentMatches", Tag.TAG_BYTE.toInt())) { "invalid readiness observation" }
            state.readiness = InventoryReadiness(matching, value.getBoolean("equipmentMatches"), free)
        }
        state.withdrawals.putAll(InventoryTransferRows.read(tag,"withdrawals")); state.deliveries.putAll(InventoryTransferRows.read(tag,"deliveries"))
        require((state.withdrawals.keys + state.deliveries.keys).all { it in work.containers?.positions.orEmpty() }) { "inventory receipt names an unauthorized container" }
        InventoryTransferRows.validate(state.withdrawals,resources.entries.filterValues { it.supplied > 0 }.mapValues { it.value.supplied })
        InventoryTransferRows.validate(state.deliveries,resources.entries.filterValues { it.delivered > 0 }.mapValues { it.value.delivered })
        for (entry in InventoryWorkCodec.list(tag, "checkpoints", 8)) {
            InventoryWorkCodec.keys(entry, "position", "block", "size")
            val p = LumberjackTaskCodec.readPosition(InventoryWorkCodec.compound(entry, "position"))
            val block = entry.getString("block"); val size = InventoryWorkCodec.int(entry, "size")
            require(p in work.containers?.positions.orEmpty() && HarvestResources.validCounts(mapOf(block to 1)) && size in 1..64) { "invalid inventory endpoint checkpoint" }
            require(state.checkpoints.put(p, ContainerCheckpoint(block, size)) == null) { "duplicate inventory endpoint checkpoint" }
        }
        require(work !is CollectContainer || state.checkpoints.keys == setOf(work.source)) { "collection lacks its captured source checkpoint" }
        for (entry in InventoryWorkCodec.list(tag, "deferred", 8)) {
            val p = LumberjackTaskCodec.readPosition(entry)
            require(p in work.containers?.positions.orEmpty() && state.deferred.add(p)) { "invalid/duplicate deferred inventory endpoint" }
        }
        state.selected = if (tag.contains("selected")) LumberjackTaskCodec.readPosition(InventoryWorkCodec.compound(tag, "selected")) else null
        state.lastProblem = if (tag.contains("lastProblem")) InventoryWorkReason.valueOf(tag.getString("lastProblem")) else null
        state.selectedItem = if (tag.contains("selectedItem")) tag.getString("selectedItem") else null
        require(state.selected == null || state.selected in work.containers?.positions.orEmpty()) { "selected inventory endpoint is unauthorized" }
        require(state.selectedItem == null || state.selectedItem in goals) { "selected inventory item is unauthorized" }
        require(phase != InventoryWorkPhase.RETURN || state.selected == null && state.selectedItem == null && state.deferred.isEmpty()) { "return retains stale inventory selection" }
        return state
    }
    internal fun counts(tag: CompoundTag, key: String): Map<String, Int> {
        val counts = LumberjackTaskCodec.readCounts(tag, key)
        require(counts.size <= 16 && counts.values.sumOf { it.toLong() } <= 2304) { "inventory quantities exceed their limits" }
        return counts
    }
}
