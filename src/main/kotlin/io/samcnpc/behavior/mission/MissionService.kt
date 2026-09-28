package io.samcnpc.behavior.mission

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.inventory.InventoryFacts
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.TaskService
import io.samcnpc.behavior.task.TaskStore
import io.samcnpc.core.api.*
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/** One finite stage per NPC. Existing rule/task execution remains the only world-effect path. */
internal object MissionService {
    fun start(server: MinecraftServer, actor: ServerPlayer, npc: UUID, definitionId: String): NpcActionResult {
        val observed=OperationSupervisionApi.observe(server,actor,npc)
        if (observed.result.status != NpcActionStatus.SUCCEEDED) return observed.result
        val store=MissionStore.forServer(server)
        if (!store.canAdd(npc)) return NpcActionResult.rejected(store.problem ?: "mission storage is full")
        if (store.get(npc)?.terminal == false) return NpcActionResult.rejected("NPC already has a mission; cancel it explicitly first")
        if (observed.observation?.task?.state in setOf(OperationTaskState.RUNNING,OperationTaskState.WAITING,OperationTaskState.PAUSED))
            return NpcActionResult.rejected("NPC already has a running or paused operation")
        val definition=BehaviorRuntimeService.mission(definitionId) ?: return NpcActionResult.rejected("unknown mission: $definitionId")
        if (definition.dimension != observed.observation?.dimensionId) return NpcActionResult.rejected("mission dimension differs from NPC")
        val packs=(definition.stages.map { it.pack }+definition.guards+BehaviorRuntimeService.activePackIds().filter { it.startsWith("samcnpc:") }).distinct()
        val hashes=packs.associateWith { checkNotNull(BehaviorRuntimeService.packFingerprint(it)) }
        val record=MissionRecord(UUID.randomUUID(),npc,actor.uuid,definition,java.util.Map.copyOf(hashes),BehaviorRuntimeService.assignedPacks(server,npc))
        store.put(record)
        return NpcActionResult.succeeded("mission ${record.id} READY; first stage ${record.stageId}")
    }

    fun control(server: MinecraftServer, actor: ServerPlayer, npc: UUID, action: String): NpcActionResult {
        val observed=OperationSupervisionApi.observe(server,actor,npc)
        if (observed.result.status != NpcActionStatus.SUCCEEDED) return observed.result
        val store=MissionStore.forServer(server)
        val record=store.get(npc) ?: return NpcActionResult.rejected(store.problem ?: "NPC has no mission")
        if (action == "status") return NpcActionResult.succeeded(status(record))
        if (actor.uuid != record.summoner && !actor.hasPermissions(2)) return NpcActionResult.rejected("mission author or operator required")
        if (record.terminal) return NpcActionResult.rejected("mission is terminal")
        when (action) {
            "pause" -> {
                if (record.state !in setOf(MissionState.READY,MissionState.RUNNING)) return NpcActionResult.rejected("mission is not running")
                record.resumeState=record.state
                hold(server,record,"manual pause",MissionState.PAUSED)
            }
            "cancel" -> {
                val task=observed.observation?.task
                if (task != null && task.taskId == record.taskId && task.state in setOf(OperationTaskState.RUNNING,OperationTaskState.WAITING,OperationTaskState.PAUSED)) {
                    val result=OperationSupervisionApi.control(server,actor,npc,controlRequest(task,checkNotNull(observed.observation).observedTick,OperationControl.CANCEL))
                    if (result.result.status != NpcActionStatus.SUCCEEDED) return result.result
                }
                record.state=MissionState.CANCELLED; record.detail="manually cancelled; no successor admitted"
                BehaviorRuntimeService.assignPacks(server,npc,record.previousPacks)
            }
            "resume" -> {
                if (record.state != MissionState.PAUSED || !compatible(record)) return NpcActionResult.rejected("only a compatible manual pause can resume; uncertain work requires review/cancel")
                val task=observed.observation?.task
                if (record.taskId != null && (task == null || task.taskId != record.taskId || task.definitionRevision != record.definitionRevision)) return NpcActionResult.rejected("exact task binding changed")
                if (task != null && task.taskId == record.taskId && task.state == OperationTaskState.PAUSED) {
                    val result=OperationSupervisionApi.control(server,actor,npc,controlRequest(task,checkNotNull(observed.observation).observedTick,OperationControl.RESUME))
                    if (result.result.status != NpcActionStatus.SUCCEEDED) return result.result
                }
                record.state=record.resumeState; record.detail="manual resume"
                attach(server,record)
            }
            else -> return NpcActionResult.rejected("unknown mission control")
        }
        store.setDirty()
        return NpcActionResult.succeeded(status(record))
    }

    fun tick(server: MinecraftServer, npc: NpcFacade) {
        val store=MissionStore.forServer(server)
        val record=store.get(npc.npcUuid) ?: return
        if (record.terminal || record.state in setOf(MissionState.PAUSED,MissionState.REVIEW_REQUIRED)) return
        val now=npc.snapshot().gameTime
        if (record.lastTick == now) return
        record.lastTick=now
        if (record.restored) {
            record.restored=false
            if (record.state == MissionState.READY) {
                // The operation save may contain an admitted task whose receipt never reached this file.
                hold(server,record,"uncertain transition/assignment after restart; no replay",pauseCurrent=true)
                return
            }
        }
        if (!compatible(record)) { hold(server,record,"mission or referenced pack changed/removed"); return }
        val actor=server.playerList.getPlayer(record.summoner)
        if (actor == null) { hold(server,record,"authorizing summoner unavailable"); return }
        val reply=OperationSupervisionApi.observe(server,actor,record.npc)
        if (reply.result.status != NpcActionStatus.SUCCEEDED) { hold(server,record,"authority unavailable: ${reply.result.detail}"); return }
        val observation=checkNotNull(reply.observation)
        if (observation.dimensionId != record.definition.dimension) { hold(server,record,"dimension changed"); return }
        if (record.remaining <= 0) { hold(server,record,"stage timeout; no success inferred"); return }
        record.remaining--; store.setDirty()
        val stage=record.stage
        if (record.state == MissionState.READY) {
            val assignment=BehaviorRuntimeService.assignPacks(server,record.npc,listOf(stage.pack)+record.definition.guards)
            if (assignment.status != NpcActionStatus.SUCCEEDED) { hold(server,record,assignment.detail); return }
            if (stage.order != null) {
                // READY is deliberately unsafe to replay from disk even when the returned binding was lost.
                record.detail="assignment pending"; store.setDirty()
                val result=OperationSupervisionApi.assign(server,actor,record.npc,
                    OperationAssignmentRequest(observation.task?.taskId,now,now+20,stage.order))
                if (result.result.status != NpcActionStatus.SUCCEEDED) { hold(server,record,"assignment rejected: ${result.result.detail}"); return }
                val task=result.observation?.task
                if (task == null) { hold(server,record,"assignment receipt missing; no replay",pauseCurrent=true); return }
                record.taskId=task.taskId; record.definitionRevision=task.definitionRevision
            }
            record.state=MissionState.RUNNING; record.detail="stage running"
            attach(server,record); store.setDirty(); return
        }
        val task=observation.task
        if (stage.order != null) {
            if (task == null || task.taskId != record.taskId || task.definitionRevision != record.definitionRevision) {
                hold(server,record,"bound task missing/replaced/amended; no replay"); return
            }
            if (task.state == OperationTaskState.CANCELLED) { hold(server,record,"bound task manually cancelled",MissionState.CANCELLED); return }
            if (task.state == OperationTaskState.PAUSED) {
                record.resumeState=MissionState.RUNNING
                hold(server,record,"bound task manually paused",MissionState.PAUSED); return
            }
            if (task.state == OperationTaskState.FAILED) { hold(server,record,"bound task failed: ${task.reason}"); return }
            if (task.state != OperationTaskState.COMPLETED) return
        }
        if (!record.definition.requirements.filter { it.id in stage.completion }.all { satisfied(it.predicate,record,npc,task) }) return
        // A pack-only stage receipt is the mission identity; it is never a fabricated task receipt.
        record.confirmed[stage.id]=record.taskId ?: record.id
        val next=stage.success
        if (next != null) {
            record.stageId=next; record.state=MissionState.READY
            record.remaining=record.stage.timeoutTicks; record.taskId=null; record.definitionRevision=0
            record.detail="verified stage ${stage.id}; successor pending"
        } else if (record.definition.requirements.all { satisfied(it.predicate,record,npc,task) }) {
            record.state=MissionState.COMPLETED; record.detail="all stages and final requirements verified"
            BehaviorRuntimeService.assignPacks(server,record.npc,record.previousPacks)
        } else {
            hold(server,record,"final requirements no longer hold or are unavailable")
        }
        store.setDirty()
    }

    private fun satisfied(predicate: MissionPredicate, record: MissionRecord, npc: NpcFacade, task: OperationTaskSnapshot?): Boolean = when (predicate) {
        is MissionPredicate.Inventory -> InventoryFacts.capture(npc).count(predicate.query,predicate.durability) >= predicate.count
        is MissionPredicate.Equipment -> InventoryFacts.capture(npc).equippedMatches(predicate.query,predicate.destination,predicate.durability)
        is MissionPredicate.Arrival -> {
            val p=npc.snapshot().position; val t=predicate.position
            val distance=(p.x-t.x)*(p.x-t.x)+(p.y-t.y)*(p.y-t.y)+(p.z-t.z)*(p.z-t.z)
            val space=npc.worldView().observeStandingSpace(p)
            distance <= predicate.radius*predicate.radius && space?.clear == true && space.supported && !space.inFluid
        }
        is MissionPredicate.Stock -> (npc.worldView().observeVisibleStock(predicate.query) as? NpcStockRead.Observed)?.count?.let { it >= predicate.count } == true
        is MissionPredicate.Soil -> predicate.cells.all { cell -> npc.worldView().observeBlock(cell)?.blockId == "minecraft:farmland" }
        is MissionPredicate.TaskSuccess -> predicate.stageId in record.confirmed ||
            (predicate.stageId == record.stageId && record.stage.order != null && task?.taskId == record.taskId && task?.state == OperationTaskState.COMPLETED)
    }

    private fun compatible(record: MissionRecord): Boolean = BehaviorRuntimeService.mission(record.definition.id)?.hash == record.definition.hash &&
        record.fingerprints.all { (id,hash) -> BehaviorRuntimeService.packFingerprint(id) == hash }

    private fun attach(server: MinecraftServer, record: MissionRecord) {
        val current=BehaviorRuntimeService.assignedPacks(server,record.npc)
        val packs=(current+record.stage.pack+record.definition.guards).distinct()
        val result=BehaviorRuntimeService.assignPacks(server,record.npc,packs)
        if (result.status != NpcActionStatus.SUCCEEDED) hold(server,record,"cannot retain required controller packs: ${result.detail}")
    }

    private fun hold(server: MinecraftServer, record: MissionRecord, reason: String, state: MissionState = MissionState.REVIEW_REQUIRED, pauseCurrent: Boolean = false) {
        val task=TaskStore.forServer(server).get(record.npc)
        if (task != null && (task.id == record.taskId || pauseCurrent) && !task.status.terminal && task.status != io.samcnpc.behavior.task.TaskStatus.PAUSED) {
            val result=TaskService.pause(server,record.npc)
            if (result.status != NpcActionStatus.SUCCEEDED) com.mojang.logging.LogUtils.getLogger().warn("Mission {} safety pause failed: {}",record.id,result.detail)
        }
        // Keep task controller dependencies while removing autonomous stage and guard proposals.
        val controller=task?.let { TaskService.packFor(it.primary.definition) }
        val retained=BehaviorRuntimeService.assignedPacks(server,record.npc).filter { it == controller || it != record.stage.pack && it !in record.definition.guards }
        BehaviorRuntimeService.assignPacks(server,record.npc,retained)
        record.state=state; record.detail=reason.take(512); MissionStore.forServer(server).setDirty()
        com.mojang.logging.LogUtils.getLogger().info("Mission {} NPC {} stage {} {}: {}",record.id,record.npc,record.stageId,state,record.detail)
    }
    private fun controlRequest(task: OperationTaskSnapshot,tick: Long,control: OperationControl) =
        OperationControlRequest(task.taskId,task.controlRevision,task.definitionRevision,tick,tick+20,control)
    private fun status(record: MissionRecord) = "mission=${record.id} definition=${record.definition.id} stage=${record.stageId} task=${record.taskId} state=${record.state} remaining=${record.remaining} confirmed=${record.confirmed.keys} ${record.detail}"
}
