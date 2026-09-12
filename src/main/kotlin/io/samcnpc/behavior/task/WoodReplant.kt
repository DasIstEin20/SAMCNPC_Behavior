package io.samcnpc.behavior.task

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import kotlin.math.abs

/** A finite post-delivery stage; it can plant only complete layouts of confirmed removed logs. */
internal object WoodReplant {
    fun completed(event: NpcActionCompletedEvent) {
        if(event.result.channel != NpcActionChannel.BLOCK_ACTION) return
        val server=BehaviorRuntimeService.serverOrNull() ?: return
        val store=TaskStore.forServer(server); val record=store.get(event.handle.npcUuid) ?: return
        val d=record.primary.definition as? LumberjackTaskDefinition ?: return
        if(d.replant == null || record.status.terminal) return
        val state=record.primary.lumberjack ?: return
        val pending=state.pendingBreak ?: return
        if(state.pendingActionId == null || state.pendingActionId != event.result.actionId) return
        state.pendingActionId=null
        if(event.result.status != NpcActionStatus.SUCCEEDED || !d.wood.matches(pending.blockId)) return
        val npc=CoreNpcApi.service(server).runtime(event.handle) ?: return
        if(npc.worldView().observeBlock(pending.position)?.isAir != true || state.observedRemovedBlocks.size >= LumberjackTaskState.MAX_REMOVED_BLOCKS && pending.position !in state.observedRemovedBlocks) {
            state.resources.uncertain=true; record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,"completed wood replant source lacks bounded actual removal")
        } else {
            state.observedRemovedBlocks.add(pending.position)
            if(state.removedWood.add(pending.position) && d.replant.work.area.contains(pending.position)) state.eligibleReplantBases=null
        }
        store.changed()
    }
    fun eligibleBases(d: PlantingTaskDefinition,removedWood: Set<NpcBlockPosition>): List<NpcBlockPosition> {
        val work=d.work; val bases=mutableListOf<NpcBlockPosition>()
        for(base in removedWood.filter(work.area::contains).sortedWith(compareBy<NpcBlockPosition> { it.y }.thenBy { it.z }.thenBy { it.x })) {
            if(!work.area.contains(base) || work.positions != null && base !in work.positions) continue
            if(work.species.footprint(base).any { !work.area.contains(it) || it !in removedWood }) continue
            if(bases.any { maxOf(abs(it.x-base.x),abs(it.z-base.z)) < work.spacing }) continue
            bases.add(base); if(bases.size == 128) break
        }
        return java.util.List.copyOf(bases)
    }
    fun readyForDelivery(state: LumberjackTaskState,d: PlantingTaskDefinition): Boolean = cachedBases(state,d).size >= d.quantity
    private fun cachedBases(state: LumberjackTaskState,d: PlantingTaskDefinition): List<NpcBlockPosition> {
        val cached=state.eligibleReplantBases
        if(cached != null) return cached
        val observed=eligibleBases(d,state.removedWood); state.eligibleReplantBases=observed
        return observed
    }
    fun begin(record: TaskRecord,npc: NpcFacade): NpcActionResult {
        val wood=checkNotNull(record.primary.lumberjack); val primary=record.primary.definition as LumberjackTaskDefinition
        val supplied=checkNotNull(primary.replant)
        if(wood.job.phase != io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase.DEPOSIT_WOOD || wood.job.pillarSession?.placedPositions.orEmpty().isNotEmpty()) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"wood delivered but replant waits on unfinished cleanup; actual work report retained")
            return NpcActionResult.failed(record.detail)
        }
        val bases=cachedBases(wood,supplied)
        if(bases.isEmpty()) {
            record.finish(TaskStatus.FAILED,TaskReason.MISSING_RESOURCE,"wood delivered but no complete authorized cut-base layout is available for replanting")
            return NpcActionResult.failed(record.detail)
        }
        val old=supplied.work
        val work=PlantingWorkOrder(old.area,old.species,PlantingMode.GAPS,old.spacing,bases,old.sources,old.keepSaplings,old.sourceKeep)
        val d=supplied.copy(work=work)
        val problem=d.validationProblem()
        val captured=TaskPlanting.capture(npc)
        if(problem != null || captured == null) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,problem ?: "cannot capture bounded sapling inventory after wood delivery")
            return NpcActionResult.failed(record.detail)
        }
        wood.replantDefinition=d; record.primary.planting=captured
        return NpcActionResult.running("wood delivered and cleanup ended; replanting confirmed cut bases with the same planting executor")
    }
    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val wood=checkNotNull(record.primary.lumberjack); val d=checkNotNull(wood.replantDefinition); val s=checkNotNull(record.primary.planting)
        val action=TaskPlanting.step(record,e,npc,world,d,s)
        if(!record.status.terminal && s.phase == PlantingPhase.DONE) {
            if(s.stop == null && s.goal(d)) record.completeActive(TaskReason.DELIVERED,"wood delivery and replant finished; ${s.detail}")
            else record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"wood delivered; ${s.detail}")
        }
        return action
    }
    fun matchesRequested(actual: PlantingTaskDefinition,requested: PlantingTaskDefinition,removed: Set<NpcBlockPosition>): Boolean {
        if(actual.copy(work=requested.work) != requested) return false
        val positions=actual.work.positions ?: return false
        val expected=eligibleBases(requested,removed)
        return positions == expected && actual.work == PlantingWorkOrder(requested.work.area,requested.work.species,PlantingMode.GAPS,requested.work.spacing,expected,requested.work.sources,requested.work.keepSaplings,requested.work.sourceKeep)
    }
}
