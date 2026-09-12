package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorAssignmentStore
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag

/** Strict bounded codec; no active Core action, route, claim or execution generation is written. */
internal object TaskCodec {
    fun write(record: TaskRecord): CompoundTag {
        val tag = CompoundTag()
        tag.putUUID("npcUuid", record.npcUuid)
        tag.putUUID("taskId", record.id)
        tag.putString("status", record.status.name)
        tag.putString("reason", record.reason.name)
        tag.putString("detail", record.detail)
        tag.putInt("totalFailures", record.totalFailures)
        tag.putInt("completedInterruptions", record.completedInterruptions)
        if (record.amendments.receipts.isNotEmpty() || record.amendments.pending != null) tag.put("amendments", TaskAmendmentCodec.write(record.amendments))
        tag.put("reaction", TaskReactionCodec.write(record.reaction))
        record.lastCombat?.let { tag.put("lastCombat", TaskCombatCodec.writeOutcome(it)) }
        if (record.logistics.policy.enabled || record.logistics.cooldownRemaining > 0 || record.logistics.outcomes.isNotEmpty() || record.frames.any { it.inventory != null }) tag.put("logistics", TaskLogisticsCodec.write(record.logistics))
        val previous = ListTag()
        for (id in record.previousPacks) previous.add(StringTag.valueOf(id))
        tag.put("previousPacks", previous)
        val frames = ListTag()
        for (frame in record.frames) {
            val entry = CompoundTag()
            entry.putUUID("frameId", frame.id)
            entry.put("definition", writeDefinition(frame.definition))
            entry.putInt("remainingTicks", frame.remainingTicks)
            entry.putInt("failures", frame.failures)
            entry.putInt("waitTicks", frame.waitTicks)
            entry.putString("reason", frame.reason.name)
            frame.resources?.let { entry.put("resources", ResourceProgressCodec.write(it)) }
            frame.lumberjack?.let { entry.put("lumberjack", LumberjackTaskCodec.write(it)) }
            frame.combat?.let { entry.put("combat", CombatTacticsCodec.writeState(it)) }
            frame.transport?.let { entry.put("transport", TransportTaskCodec.write(it)) }
            frame.inventory?.let { entry.put("inventory", InventoryStateCodec.write(it)) }
            frame.mining?.let { entry.put("mining", MiningStateCodec.write(it)) }
            frame.food?.let { entry.put("food", FoodStateCodec.write(it)) }
            frame.explorer?.let { entry.put("explorer", ExplorerTaskCodec.write(it)) }
            frame.fishing?.let { entry.put("fishing", FishingTaskCodec.write(it)) }
            frame.machine?.let { entry.put("machine", MachineTaskCodec.write(it)) }
            frame.planting?.let { entry.put("planting", PlantingStateCodec.write(it)) }
            frame.farming?.let { entry.put("farming", FarmStateCodec.write(it)) }
            frames.add(entry)
        }
        tag.put("frames", frames)
        record.reconciledPosition?.let { tag.put("observedPosition", position(it)) }
        return tag
    }

    fun read(tag: CompoundTag, sourceVersion: Int = 8): TaskRecord {
        require(tag.hasUUID("npcUuid") && tag.hasUUID("taskId")) { "missing NPC/task UUID" }
        val status = TaskStatus.entries.firstOrNull { it.name == tag.getString("status") } ?: errorValue("unknown task status")
        val reason = reason(tag)
        val reaction = if (tag.contains("reaction")) TaskReactionCodec.read(compound(tag, "reaction"), sourceVersion) else {
            require(sourceVersion < 4) { "v4 task is missing reaction policy" }
            TaskReactionState()
        }
        val lastCombat = if (tag.contains("lastCombat")) TaskCombatCodec.readOutcome(compound(tag, "lastCombat")) else null
        val detail = tag.getString("detail")
        require(detail.length <= TaskRecord.MAX_DETAIL_LENGTH) { "oversized task detail" }
        val previous = list(tag, "previousPacks", Tag.TAG_STRING, 8).map { it.asString }
        require(BehaviorAssignmentStore.validPackIds(previous)) { "invalid previous assignments" }
        val frames = list(tag, "frames", Tag.TAG_COMPOUND, TaskRecord.MAX_FRAMES).map { element ->
            val entry = element as CompoundTag
            require(entry.hasUUID("frameId")) { "missing frame UUID" }
            val definition = readDefinition(compound(entry, "definition"))
            val remaining = entry.int("remainingTicks")
            val failures = entry.int("failures")
            val wait = entry.int("waitTicks")
            require(remaining in 0..definition.budget.ticks && failures in 0..definition.budget.attempts && wait in 0..200) { "invalid task frame budget" }
            val resources = when (definition) {
                is NavigateTaskDefinition, is LumberjackTaskDefinition, is AttackTaskDefinition, is CombatMissionDefinition, is TransportTaskDefinition, is InventoryTaskDefinition, is MiningTaskDefinition, is FoodTaskDefinition, is FarmTaskDefinition, is PlantingTaskDefinition, is MachineTaskDefinition, is FishingTaskDefinition, is ExplorerTaskDefinition -> { require(!entry.contains("resources")) { "navigation has unexpected resources" }; null }
                is DeliveryTaskDefinition -> if (definition.version == 1) ResourceProgressCodec.read(compound(entry, "resources"), definition.quantity)
                    else { require(!entry.contains("resources")) { "adaptive delivery contains legacy mutable accounting" }; null }
            }
            val lumberjack = if (definition is LumberjackTaskDefinition) {
                LumberjackTaskCodec.read(compound(entry, "lumberjack"), tag.getUUID("npcUuid"), definition, sourceVersion)
            } else { require(!entry.contains("lumberjack")) { "unexpected wood work state" }; null }
            val combat = if (definition is AttackTaskDefinition || definition is CombatMissionDefinition) {
                if (sourceVersion >= 5) CombatTacticsCodec.readState(compound(entry, "combat")) else {
                    require(!entry.contains("combat") && definition is AttackTaskDefinition) { "pre-v5 contains future mission/tactical state" }
                    CombatTaskState.forDefinition(definition)
                }
            } else { require(!entry.contains("combat")) { "unexpected tactical state" }; null }
            require(sourceVersion >= 5 || definition !is AttackTaskDefinition || definition.version == 1) { "pre-v5 attack contains future tactics" }
            require(sourceVersion >= 5 || definition !is LumberjackTaskDefinition || definition.version == 1) { "pre-v5 work contains future supplies" }
            if (combat != null) CombatMissionCodec.validateState(definition, combat)
            require(sourceVersion >= 5 || definition !is DeliveryTaskDefinition || definition.version == 1) { "pre-v5 contains future carried delivery" }
            val transport = if (definition is TransportTaskDefinition || definition is DeliveryTaskDefinition && definition.version == 2) {
                require(sourceVersion >= 5) { "pre-v5 contains future transport work" }
                TransportTaskCodec.read(compound(entry, "transport"), definition)
            } else { require(!entry.contains("transport")) { "unexpected cargo state" }; null }
            val inventory = if (definition is InventoryTaskDefinition) {
                require(sourceVersion >= 5) { "pre-v5 contains future inventory work" }
                InventoryStateCodec.read(compound(entry, "inventory"), definition)
            } else { require(!entry.contains("inventory")) { "unexpected inventory work state" }; null }
            val mining = if (definition is MiningTaskDefinition) {
                require(sourceVersion >= 5) { "pre-v5 contains future mining state" }
                MiningStateCodec.read(compound(entry, "mining"), definition)
            } else { require(!entry.contains("mining")) { "unexpected mining state" }; null }
            val food = if (definition is FoodTaskDefinition) {
                require(sourceVersion >= 5) { "pre-v5 contains future food state" }
                FoodStateCodec.read(compound(entry,"food"),definition)
            } else { require(!entry.contains("food")) { "unexpected food state" }; null }
            val farming=if (definition is FarmTaskDefinition) {
                require(sourceVersion >= 5) { "pre-v5 contains future farming state" }
                FarmStateCodec.read(compound(entry,"farming"),definition)
            } else { require(!entry.contains("farming")) { "unexpected farming state" }; null }
            val explorer=if (definition is ExplorerTaskDefinition) {
                require(sourceVersion >= 8) { "pre-v8 contains future explorer work" }
                ExplorerTaskCodec.read(compound(entry,"explorer"),definition)
            } else { require(!entry.contains("explorer")) { "unexpected explorer state" }; null }
            val fishing=if (definition is FishingTaskDefinition) {
                require(sourceVersion >= 7) { "pre-v7 contains future fishing work" }
                FishingTaskCodec.read(compound(entry,"fishing"),definition)
            } else { require(!entry.contains("fishing")) { "unexpected fishing work state" }; null }
            val machine=if (definition is MachineTaskDefinition) {
                require(sourceVersion >= 6) { "pre-v6 contains future machine work" }
                MachineTaskCodec.read(compound(entry,"machine"),definition)
            } else { require(!entry.contains("machine")) { "unexpected machine work state" }; null }
            val planting=if(definition is PlantingTaskDefinition) {
                require(sourceVersion >= 5) { "pre-v5 contains future planting state" }
                PlantingStateCodec.read(compound(entry,"planting"),definition)
            } else if(definition is LumberjackTaskDefinition && lumberjack?.replantDefinition != null) {
                require(sourceVersion >= 5)
                PlantingStateCodec.read(compound(entry,"planting"),checkNotNull(lumberjack.replantDefinition))
            } else { require(!entry.contains("planting")) { "unexpected planting state" }; null }
            TaskFrame(entry.getUUID("frameId"), definition, remaining, failures, wait, reason(entry), resources, lumberjack, combat, transport, inventory, mining, food, farming, planting, machine, fishing, explorer)
        }
        require(frames.isNotEmpty() && frames.map { it.id }.distinct().size == frames.size) { "missing/duplicate frame identity" }
        require(frames.all { it.definition.dimensionId == frames.first().definition.dimensionId }) { "mixed task dimensions" }
        require(status.terminal || frames.all { (it.remainingTicks > 0 || it != frames.first() && it != frames.last() && it.definition is InventoryTaskDefinition) && it.failures < it.definition.budget.attempts }) { "active task has exhausted its budget" }
        val reasons = when (status) {
            TaskStatus.RUNNING -> setOf(TaskReason.ASSIGNED, TaskReason.USER_RESUMED, TaskReason.INTERRUPTED, TaskReason.RESUMED, TaskReason.CHANNEL_UNAVAILABLE)
            TaskStatus.WAITING -> setOf(TaskReason.SOURCE_EMPTY, TaskReason.SOURCE_UNAVAILABLE, TaskReason.INVENTORY_FULL, TaskReason.DESTINATION_UNAVAILABLE, TaskReason.NO_PROGRESS, TaskReason.STORAGE_FULL, TaskReason.USER_RESUMED, TaskReason.RESUMED)
            TaskStatus.PAUSED -> setOf(TaskReason.USER_PAUSED)
            TaskStatus.COMPLETED -> setOf(TaskReason.ARRIVED, TaskReason.DELIVERED, TaskReason.TARGET_DEFEATED, TaskReason.DEFENSE_FINISHED, TaskReason.AREA_CLEARED, TaskReason.PATROL_FINISHED, TaskReason.INVENTORY_FINISHED, TaskReason.MINING_FINISHED, TaskReason.FOOD_FINISHED, TaskReason.FARM_FINISHED, TaskReason.PLANTING_FINISHED, TaskReason.MACHINE_FINISHED, TaskReason.FISHING_FINISHED, TaskReason.EXPLORATION_FINISHED)
            TaskStatus.CANCELLED -> setOf(TaskReason.USER_CANCELLED, TaskReason.ASSIGNMENT_CHANGED, TaskReason.NPC_REMOVED,
                TaskReason.TARGET_ENDED, TaskReason.TARGET_UNAVAILABLE, TaskReason.LEASH_REACHED, TaskReason.PERMISSION_CHANGED, TaskReason.COMBAT_TIME_LIMIT, TaskReason.RECOVERY_EXHAUSTED, TaskReason.SUBJECT_UNAVAILABLE)
            TaskStatus.FAILED -> setOf(TaskReason.RETRY_LIMIT, TaskReason.TIME_LIMIT, TaskReason.DIMENSION_CHANGED, TaskReason.STATE_MISMATCH, TaskReason.MISSING_RESOURCE, TaskReason.WORK_FAILED, TaskReason.COMBAT_NO_PROGRESS, TaskReason.INVENTORY_INCOMPLETE)
        }
        require(reason in reasons) { "task reason is inconsistent with its status" }
        require(frames.drop(1).all { it.definition is NavigateTaskDefinition || it.definition is AttackTaskDefinition || it.definition is InventoryTaskDefinition }) { "unsupported resource interruption" }
        require(frames.count { it.definition is InventoryTaskDefinition } <= 1 && frames.drop(2).none { it.definition is InventoryTaskDefinition }) { "nested inventory frames are forbidden" }
        require(frames.count { it.definition is AttackTaskDefinition } <= 1) { "nested combat frames are forbidden" }
        frames.first().food?.let { food ->
            if (food.huntTarget != null) {
                val hunt=frames.firstOrNull { it.id == food.huntFrame }
                require(if (hunt != null) (hunt.definition as? AttackTaskDefinition)?.targetUuid == food.huntTarget else lastCombat?.targetUuid == food.huntTarget) { "food hunt lacks matching attack frame or outcome" }
            }
        }
        // v4 could withdraw an unmarked attack when switching retaliation off. Preserve that migration behavior.
        if (sourceVersion == 4 && reaction.policy.mode == TaskReactionMode.RETALIATE) {
            reaction.activeFrame = frames.drop(1).firstOrNull { it.definition is AttackTaskDefinition }?.id
        }
        require(reaction.activeFrame == null || frames.drop(1).any { it.id == reaction.activeFrame && it.definition is AttackTaskDefinition }) { "reaction frame is absent or not an attack interruption" }
        require(reaction.policy.subjectUuid != tag.getUUID("npcUuid")) { "NPC cannot be its own protected subject" }
        require(status.terminal || frames.none { it.resources?.uncertain == true || it.lumberjack?.resources?.uncertain == true || it.transport?.ledger?.uncertain == true || it.inventory?.resources?.uncertain == true || it.mining?.resources?.physical?.uncertain == true || it.food?.resources?.physical?.uncertain == true || it.farming?.resources?.physical?.uncertain == true || it.planting?.resources?.uncertain == true || it.machine?.resources?.uncertain == true || it.fishing?.resources?.uncertain == true }) { "active task has uncertain resources" }
        require(status.terminal || frames.none { it.fishing?.pendingReel == true }) { "unconfirmed fishing payout; saved record preserved without replay" }
        require(status.terminal || frames.none { it.explorer?.phase == ExplorerPhase.DONE }) { "active explorer claims completed return" }
        if (status == TaskStatus.COMPLETED) {
            when (val primary = frames.first().definition) {
                is ExplorerTaskDefinition -> {
                    val state=checkNotNull(frames.first().explorer)
                    require(reason == TaskReason.EXPLORATION_FINISHED && state.phase == ExplorerPhase.DONE && state.cursor == 0 && state.stop != null) { "exploration completion lacks confirmed return" }
                    require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),primary.anchor) <= 0.75*0.75) { "exploration completion lacks physical return" }
                }
                is FishingTaskDefinition -> {
                    val state=checkNotNull(frames.first().fishing)
                    require(reason == TaskReason.FISHING_FINISHED && state.phase == FishingPhase.DONE && state.caught == primary.catches && state.drops.isEmpty() && !state.pendingReel) { "fishing completion lacks confirmed collection" }
                    if (primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),primary.returnTo) <= 0.75*0.75) { "fishing completion lacks physical return" }
                }
                is MachineTaskDefinition -> {
                    val state=checkNotNull(frames.first().machine)
                    require(reason == TaskReason.MACHINE_FINISHED && state.phase == MachinePhase.DONE && state.goal(primary)) { "machine completion lacks actual feed/output counts" }
                    if (primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),primary.returnTo) <= 0.75*0.75) { "machine completion lacks physical return" }
                }
                is PlantingTaskDefinition -> {
                    val state=checkNotNull(frames.first().planting)
                    require(reason == TaskReason.PLANTING_FINISHED && state.phase == PlantingPhase.DONE && state.stop == null && state.goal(primary)) { "planting completion lacks actual layout quota" }
                    if(primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),primary.returnTo) <= 0.75*0.75) { "planting completion lacks physical return" }
                }
                is FarmTaskDefinition -> {
                    val state=checkNotNull(frames.first().farming)
                    require(reason == TaskReason.FARM_FINISHED && state.phase == FarmPhase.RETURN && state.stop == null && state.goal(primary) && state.deliverable(primary) == 0) { "farm completion lacks physical crop yield/replant or final delivery" }
                    if (primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),primary.returnTo) <= 0.75*0.75) { "farm completion lacks physical return" }
                }
                is FoodTaskDefinition -> {
                    val state=checkNotNull(frames.first().food)
                    require(reason == TaskReason.FOOD_FINISHED && state.phase == FoodPhase.RETURN && state.stop == null && state.goal(primary)) { "food completion lacks physical delivery, ration or terminal phase" }
                    if (primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),primary.returnTo) <= 0.75*0.75) { "food completion lacks physical return" }
                }
                is MiningTaskDefinition -> {
                    val state = checkNotNull(frames.first().mining)
                    require(reason == TaskReason.MINING_FINISHED && state.phase == MiningPhase.RETURN && state.stop == null && state.goal(primary) && state.cargo(primary) == 0) { "mining completion lacks its counting basis, delivery or terminal phase" }
                    if (primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag, "observedPosition")),primary.returnTo) <= 0.75*0.75) { "mining completion lacks physical return" }
                }
                is InventoryTaskDefinition -> require(reason == TaskReason.INVENTORY_FINISHED && frames.first().inventory?.reason == InventoryWorkReason.SATISFIED && frames.first().inventory?.satisfied(primary.work) == true && tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag, "observedPosition")), primary.returnTo) <= 0.75 * 0.75) { "inventory completion lacks a satisfied goal and physical return" }
                is CombatMissionDefinition -> CombatMissionCodec.validateCompletion(primary, frames.first(), reason,
                    if (tag.contains("observedPosition")) readPosition(compound(tag, "observedPosition")) else null)
                is AttackTaskDefinition -> require(reason == TaskReason.TARGET_DEFEATED && lastCombat != null && lastCombat.targetUuid == primary.targetUuid &&
                    lastCombat.status == TaskStatus.COMPLETED && lastCombat.confirmedKills == 1) { "attack completion lacks a confirmed kill" }
                is LumberjackTaskDefinition -> {
                    require(reason == TaskReason.DELIVERED && (frames.first().lumberjack?.resources?.delivered(primary.wood) ?: 0) >= primary.quantity) { "wood completion lacks confirmed net quantity" }
                    if(primary.replant != null) {
                        val planting=checkNotNull(frames.first().planting) { "wood completion lacks requested planting" }
                        val plantingDefinition=checkNotNull(frames.first().lumberjack?.replantDefinition)
                        require(planting.phase == PlantingPhase.DONE && planting.stop == null && planting.goal(plantingDefinition)) { "wood replant completion lacks actual layout quota" }
                        if(plantingDefinition.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag,"observedPosition")),plantingDefinition.returnTo) <= 0.75*0.75) { "wood replant completion lacks physical return" }
                    }
                }
                is NavigateTaskDefinition -> require(reason == TaskReason.ARRIVED) { "navigation completion reason differs" }
                is TransportTaskDefinition -> {
                    require(reason == TaskReason.DELIVERED && (frames.first().transport?.ledger?.delivered ?: 0) >= primary.quantity) { "transport completion lacks confirmed cargo delivery" }
                    if (primary.returnTo != null) require(tag.contains("observedPosition") && TaskNavigator.distanceSquared(readPosition(compound(tag, "observedPosition")), primary.returnTo) <= 0.75 * 0.75) { "transport completion lacks physical return" }
                }
                is DeliveryTaskDefinition -> require(reason == TaskReason.DELIVERED && (if (primary.version == 1) frames.first().resources?.delivered == primary.quantity else (frames.first().transport?.ledger?.delivered ?: 0) >= primary.quantity)) { "delivery completion lacks confirmed quantity" }
            }
        }
        require(status != TaskStatus.COMPLETED || frames.size == 1) { "completed task retains an unfinished interruption" }
        if (status == TaskStatus.WAITING) require(frames.last().waitTicks > 0) { "waiting task has no wake deadline" }
        if (status == TaskStatus.RUNNING) require(frames.last().waitTicks == 0) { "running task has an unresolved wait" }
        val failures = tag.int("totalFailures")
        val completed = tag.int("completedInterruptions")
        require(failures in frames.sumOf { it.failures }..264 && completed in 0..32) { "invalid accumulated task counters" }
        require(completed + frames.size - 1 <= 32) { "interruption budget exceeded" }
        val observed = if (tag.contains("observedPosition")) readPosition(compound(tag, "observedPosition")) else null
        val amendments = if (tag.contains("amendments")) {
            require(sourceVersion >= 5) { "pre-v5 contains future amendment state" }
            TaskAmendmentCodec.read(compound(tag, "amendments"), tag.getUUID("taskId"))
        } else TaskAmendmentState(tag.getUUID("taskId"))
        require(!status.terminal || amendments.pending == null) { "terminal task retains a pending amendment" }
        val logistics = if (tag.contains("logistics")) {
            require(sourceVersion >= 5) { "pre-v5 contains future logistics policy/state" }
            TaskLogisticsCodec.read(compound(tag, "logistics"))
        } else TaskLogisticsState()
        require(logistics.outcomes.size <= completed + if (frames.first().definition is InventoryTaskDefinition || status.terminal && frames.any { it.inventory != null }) 1 else 0) { "inventory history exceeds ended frame count" }
        frames.first().planting?.supplyFrame?.let { id ->
            require(frames.any { it.id == id && it.definition is InventoryTaskDefinition } || logistics.outcomes.any { it.frameId == id }) { "sapling supply lacks its exact inventory frame/outcome" }
        }
        frames.first().farming?.supplyFrame?.let { id ->
            require(frames.any { it.id == id && it.definition is InventoryTaskDefinition } || logistics.outcomes.any { it.frameId == id }) { "farm seed request lacks its inventory frame/outcome" }
        }
        require(status.terminal || logistics.outcomes.none { outcome -> frames.any { it.id == outcome.frameId } }) { "active inventory frame already has a final report" }
        return TaskRecord(tag.getUUID("npcUuid"), tag.getUUID("taskId"), frames, previous,
            status, reason, detail, observed, failures, completed, reaction, lastCombat, amendments, logistics)
    }

    internal fun writeDefinition(definition: TaskDefinition): CompoundTag {
        val tag = CompoundTag()
        tag.putString("id", definition.operationId)
        tag.putInt("version", definition.version)
        tag.putString("dimension", definition.dimensionId)
        tag.putInt("duration", definition.budget.ticks)
        tag.putInt("attempts", definition.budget.attempts)
        tag.putInt("backoff", definition.budget.backoffTicks)
        when (definition) {
            is ExplorerTaskDefinition -> ExplorerTaskCodec.writeDefinition(definition,tag)
            is FishingTaskDefinition -> FishingTaskCodec.writeDefinition(definition,tag)
            is MachineTaskDefinition -> MachineTaskCodec.writeDefinition(definition,tag)
            is PlantingTaskDefinition -> PlantingOrderCodec.writeDefinition(definition,tag)
            is FarmTaskDefinition -> FarmOrderCodec.writeDefinition(definition, tag)
            is FoodTaskDefinition -> FoodOrderCodec.writeDefinition(definition, tag)
            is MiningTaskDefinition -> MiningOrderCodec.writeDefinition(definition, tag)
            is InventoryTaskDefinition -> InventoryWorkCodec.writeDefinition(definition, tag)
            is TransportTaskDefinition -> TransportTaskCodec.writeDefinition(definition, tag)
            is CombatMissionDefinition -> CombatMissionCodec.write(definition, tag)
            is AttackTaskDefinition -> TaskCombatCodec.writeDefinition(definition, tag)
            is LumberjackTaskDefinition -> LumberjackTaskCodec.writeDefinition(definition, tag)
            is NavigateTaskDefinition -> {
                tag.put("destination", position(definition.destination))
                tag.putFloat("speed", definition.speed)
                tag.putDouble("arrivalDistance", definition.arrivalDistance)
            }
            is DeliveryTaskDefinition -> {
                tag.put("container", CompoundTag().apply {
                    putInt("x", definition.destination.x); putInt("y", definition.destination.y); putInt("z", definition.destination.z)
                })
                tag.putString("item", definition.itemId)
                tag.putInt("quantity", definition.quantity)
                tag.putInt("keepAtLeast", definition.keepAtLeast)
                definition.anchor?.let { tag.put("anchor", position(it)) }
            }
        }
        return tag
    }

    internal fun readDefinition(tag: CompoundTag): TaskDefinition {
        val id = tag.getString("id")
        val common = setOf("id", "version", "dimension", "duration", "attempts", "backoff")
        val budget = TaskBudget(tag.int("duration"), tag.int("attempts"), tag.int("backoff"))
        val dimension = tag.getString("dimension")
        val version = tag.int("version")
        val definition = when (id) {
            ExplorerTaskDefinition.ID -> {
                require(tag.allKeys == common+ExplorerTaskCodec.definitionKeys) { "unknown/missing explorer parameter" }
                ExplorerTaskCodec.readDefinition(tag,dimension,budget,version)
            }
            FishingTaskDefinition.ID -> {
                require(tag.allKeys == common+FishingTaskCodec.definitionKeys(tag.contains("returnTo"))) { "unknown/missing fishing parameter" }
                FishingTaskCodec.readDefinition(tag,dimension,budget,version)
            }
            MachineTaskDefinition.ID -> {
                require(tag.allKeys == common+MachineTaskCodec.definitionKeys(tag.contains("returnTo"))) { "unknown/missing machine parameter" }
                MachineTaskCodec.readDefinition(tag,dimension,budget,version)
            }
            PlantingTaskDefinition.ID -> {
                require(tag.allKeys == common+PlantingOrderCodec.keys(tag.contains("returnTo"))) { "unknown/missing planting parameter" }
                PlantingOrderCodec.readDefinition(tag,dimension,budget,version)
            }
            FarmTaskDefinition.ID -> {
                require(tag.allKeys == common + FarmOrderCodec.keys(tag.contains("returnTo"))) { "unknown/missing farm parameter" }
                FarmOrderCodec.readDefinition(tag,dimension,budget,version)
            }
            FoodTaskDefinition.ID -> {
                require(tag.allKeys == common + FoodOrderCodec.keys(tag.contains("returnTo"))) { "unknown/missing food parameter" }
                FoodOrderCodec.readDefinition(tag,dimension,budget,version)
            }
            MiningTaskDefinition.ID -> {
                require(tag.allKeys == common + MiningOrderCodec.keys(tag.contains("returnTo"))) { "unknown/missing mining parameter" }
                MiningOrderCodec.readDefinition(tag, dimension, budget, version)
            }
            InventoryTaskDefinition.ID -> {
                require(tag.allKeys == common + InventoryWorkCodec.definitionKeys()) { "unknown/missing inventory parameter" }
                InventoryWorkCodec.readDefinition(tag, dimension, budget, version)
            }
            TransportTaskDefinition.ID -> {
                require(tag.allKeys == common + TransportTaskCodec.keys(tag.contains("returnTo"))) { "unknown/missing transport parameter" }
                TransportTaskCodec.readDefinition(tag, dimension, budget, version)
            }
            DefendTaskDefinition.ID, AreaAttackTaskDefinition.ID, PatrolTaskDefinition.ID -> {
                require(tag.allKeys == common + CombatMissionCodec.keys(id, tag.contains("subject"), tag.contains("supportTarget"))) { "unknown/missing combat mission parameter" }
                CombatMissionCodec.read(tag, id, dimension, budget, version)
            }
            AttackTaskDefinition.ID -> {
                require(tag.allKeys == common + TaskCombatCodec.definitionKeys(version)) { "unknown/missing attack parameter" }
                TaskCombatCodec.readDefinition(tag, dimension, budget, version)
            }
            LumberjackTaskDefinition.ID -> {
                require(tag.allKeys == common + LumberjackTaskCodec.definitionKeys(version,tag.contains("replant"))) { "unknown/missing lumberjack parameter" }
                LumberjackTaskCodec.readDefinition(tag, dimension, budget, version)
            }
            NavigateTaskDefinition.ID -> {
                require(tag.allKeys == common + setOf("destination", "speed", "arrivalDistance")) { "unknown/missing navigation parameter" }
                require(tag.contains("speed", Tag.TAG_FLOAT.toInt())) { "invalid speed type" }
                NavigateTaskDefinition(dimension, readPosition(compound(tag, "destination")), tag.getFloat("speed"), tag.double("arrivalDistance"), budget, version)
            }
            DeliveryTaskDefinition.ID -> {
                require(tag.allKeys == common + setOf("container", "item", "quantity", "keepAtLeast") + (if (version >= 2) setOf("anchor") else emptySet())) { "unknown/missing delivery parameter" }
                val position = compound(tag, "container")
                DeliveryTaskDefinition(dimension, io.samcnpc.core.api.NpcBlockPosition(position.int("x"), position.int("y"), position.int("z")),
                    tag.getString("item"), tag.int("quantity"), tag.int("keepAtLeast"), budget, version, if (version >= 2) readPosition(compound(tag, "anchor")) else null)
            }
            else -> errorValue("unknown operation $id")
        }
        val problem = definition.validationProblem()
        require(problem == null) { problem.orEmpty() }
        return definition
    }

    private fun position(position: NpcPosition) = CompoundTag().apply {
        putDouble("x", position.x); putDouble("y", position.y); putDouble("z", position.z)
    }
    private fun readPosition(tag: CompoundTag): NpcPosition {
        val position = NpcPosition(tag.double("x"), tag.double("y"), tag.double("z"))
        require(position.x.isFinite() && position.y.isFinite() && position.z.isFinite()) { "non-finite task position" }
        return position
    }
    private fun reason(tag: CompoundTag) = TaskReason.entries.firstOrNull { it.name == tag.getString("reason") } ?: errorValue("unknown task reason")
    private fun compound(tag: CompoundTag, key: String): CompoundTag {
        require(tag.contains(key, Tag.TAG_COMPOUND.toInt())) { "missing compound $key" }
        return tag.getCompound(key)
    }
    private fun list(tag: CompoundTag, key: String, type: Byte, limit: Int): ListTag {
        val list = tag.get(key) as? ListTag ?: errorValue("missing list $key")
        require(list.size <= limit && (list.isEmpty() || list.elementType == type)) { "invalid/oversized list $key" }
        return list
    }
    private fun CompoundTag.int(key: String): Int {
        require(contains(key, Tag.TAG_INT.toInt())) { "missing integer $key" }
        return getInt(key)
    }
    private fun CompoundTag.double(key: String): Double {
        require(contains(key, Tag.TAG_DOUBLE.toInt())) { "missing double $key" }
        return getDouble(key)
    }
    private fun errorValue(message: String): Nothing = throw IllegalArgumentException(message)
}
