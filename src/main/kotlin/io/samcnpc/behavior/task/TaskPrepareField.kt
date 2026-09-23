package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.HarvestWorkClaims
import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.kernel.work.SpatialWorkClaimKernel
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

internal object TaskPrepareField {
    fun capture(npc: NpcFacade): FieldPreparationState? =
        if (HarvestResources.validCounts(HarvestResources.inventoryCounts(npc))) FieldPreparationState(HarvestResources.capture(npc)) else null

    fun observe(record: TaskRecord,npc: NpcFacade): String? {
        val s=record.primary.fieldPreparation ?: return null
        val actual=HarvestResources.inventoryCounts(npc)
        return s.resources.reconcileLoad(npc.inventoryLoadSnapshot(),actual) ?: s.resources.observeLive(actual)
    }

    fun tick(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView): NpcActionResult {
        val d=record.primary.definition as PrepareFieldTaskDefinition
        val s=checkNotNull(record.primary.fieldPreparation)
        val snapshot=npc.snapshot();record.reconciledPosition=snapshot.position
        if (snapshot.dimensionId!=d.dimensionId || world.dimensionId!=d.dimensionId || !d.contains(snapshot.position)) {
            record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,"field preparation left its fixed travel boundary")
            return NpcActionResult.failed(record.detail)
        }
        observe(record,npc)?.let { return mismatch(record,s,it) }
        if (s.reconcileWorld) {
            if (!BehaviorPlanning.admit(world,16,PlanningKind.RECONCILIATION)) return NpcActionResult.running("field reconciliation queued")
            val end=minOf(s.reconcileCursor+16,d.cells.size)
            while (s.reconcileCursor<end) {
                val index=s.reconcileCursor++;val position=d.cells[index]
                if (position !in s.confirmed) continue
                val block=world.observeBlock(position) ?: return stop(e,npc,s,FieldProblem.UNOBSERVABLE,"saved soil is unavailable")
                if (block.blockId!="minecraft:farmland") {
                    s.confirmed.remove(position)
                    if (s.stop==null) { s.cursor=minOf(s.cursor,index);s.phase=FieldPhase.WORK }
                }
            }
            if (s.reconcileCursor<d.cells.size) return NpcActionResult.running("rechecking saved prepared soil")
            s.reconcileWorld=false;s.reconcileCursor=0
        }
        when (s.phase) {
            FieldPhase.WORK -> return work(record,e,npc,world,d,s)
            FieldPhase.RETURN -> {
                HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
                if (d.returnTo!=null) {
                    val result=TaskNavigator.move(record,e,npc,world,NavigateTaskDefinition(d.dimensionId,d.returnTo,budget=d.budget))
                    if (result.status!=NpcActionStatus.SUCCEEDED) return result
                }
                if (s.stop!=null) return finish(record,s,false)
                s.phase=FieldPhase.VERIFY;s.cursor=0
            }
            FieldPhase.VERIFY -> {
                if (!BehaviorPlanning.admit(world,32,PlanningKind.GENERAL)) return NpcActionResult.running("final field observations queued")
                repeat(minOf(16,d.cells.size-s.cursor)) {
                    val soil=d.cells[s.cursor]
                    val block=world.observeBlock(soil)
                    val above=world.observeBlock(above(soil))
                    if (block==null || above==null) return stop(e,npc,s,FieldProblem.UNOBSERVABLE,"final field facts are unavailable")
                    if (block.blockId!="minecraft:farmland" || !above.isAir) {
                        s.confirmed.remove(soil);s.phase=FieldPhase.WORK
                        e.rejectedWorkStances.clear();TaskInventory.resetRoute(e,npc)
                        return NpcActionResult.running("field changed before final confirmation; recheck within the original use bound")
                    }
                    s.cursor++
                }
                if (s.cursor==d.cells.size) return finish(record,s,true)
            }
            FieldPhase.DONE -> return if (s.stop==null) NpcActionResult.succeeded(s.detail) else NpcActionResult.failed(s.detail)
        }
        record.detail="field ${s.phase}; prepared=${s.confirmed.size}/${d.cells.size}"
        return NpcActionResult.running(record.detail)
    }

    private fun work(record: TaskRecord,e: TaskExecution,npc: NpcFacade,world: NpcWorldView,d: PrepareFieldTaskDefinition,s: FieldPreparationState): NpcActionResult {
        if (s.cursor==d.cells.size) { s.phase=FieldPhase.RETURN;TaskInventory.resetRoute(e,npc);return NpcActionResult.running("returning before final field confirmation") }
        if (!BehaviorPlanning.admit(world,4,PlanningKind.GENERAL)) return NpcActionResult.running("soil observations queued")
        val soil=d.cells[s.cursor];val air=above(soil)
        val block=world.observeBlockDetails(soil);val cover=world.observeBlock(air)
        if (block==null || block.environment==null || cover==null) return stop(e,npc,s,FieldProblem.UNOBSERVABLE,"soil or air facts are unavailable")
        if (!cover.isAir) return stop(e,npc,s,FieldProblem.BLOCKED,"soil has an occupied cell above; no clearing is authorized")
        if (block.blockId=="minecraft:farmland") return confirmed(record,e,npc,s,soil)
        if (block.blockId !in SoilPreparation.tillable || block.environment?.fluidId!=null)
            return stop(e,npc,s,FieldProblem.INVALID_SOIL,"unsupported soil ${block.blockId}")
        if ((s.attempts[soil] ?: 0)>=FieldPreparationState.MAX_ATTEMPTS_PER_CELL)
            return stop(e,npc,s,FieldProblem.CHANGED_LIMIT,"soil changed beyond the finite preparation allowance")
        val hoe=SoilPreparation.carriedHoe(npc) ?: return stop(e,npc,s,FieldProblem.MISSING_HOE,"no actual carried hoe")
        // Farm and sapling work reserve the air cell above this same soil.
        val lease=HarvestWorkClaims.kernel.renewOrClaim(npc.npcUuid,d.dimensionId,air,npc.snapshot().gameTime,record.id)
        if (lease!=SpatialWorkClaimKernel.Result.Acquired && lease!=SpatialWorkClaimKernel.Result.Held)
            return NpcActionResult.running("waiting for fair soil preparation access")
        val approach=WorkInteractionApproach.move(record,e,npc,world,d,soil)
        if (approach!=null) return if (approach.status==NpcActionStatus.FAILED) stop(e,npc,s,FieldProblem.UNREACHABLE,approach.detail) else approach
        s.attempts[soil]=(s.attempts[soil] ?: 0)+1
        when (val result=SoilPreparation.use(npc,world,soil,hoe)) {
            is SoilPreparation.Result.EquipRejected -> return stop(e,npc,s,FieldProblem.MISSING_HOE,result.action.detail)
            is SoilPreparation.Result.Used -> {
                if (result.action.code==NpcActionCode.EFFECT_UNCERTAIN) return mismatch(record,s,"uncertain native soil effect; no retry: ${result.action.detail}")
                result.consumptionProblem()?.let { return mismatch(record,s,it) }
                s.resources.observeStep(result.after,emptyMap(),emptyMap(),placement=true)?.let { return mismatch(record,s,it) }
                if (result.action.status!=NpcActionStatus.SUCCEEDED || !result.farmland)
                    return stop(e,npc,s,FieldProblem.NATIVE_USE_FAILED,"native hoe did not establish farmland: ${result.action.detail}")
            }
        }
        return confirmed(record,e,npc,s,soil)
    }

    private fun confirmed(record: TaskRecord,e: TaskExecution,npc: NpcFacade,s: FieldPreparationState,soil: NpcBlockPosition): NpcActionResult {
        s.confirmed.add(soil);s.cursor++;e.rejectedWorkStances.clear()
        TaskInventory.resetRoute(e,npc);HarvestWorkClaims.kernel.release(npc.npcUuid,record.id)
        return NpcActionResult.running("prepared soil observed at $soil")
    }
    private fun stop(e: TaskExecution,npc: NpcFacade,s: FieldPreparationState,why: FieldProblem,detail: String): NpcActionResult {
        s.stop=why;s.detail=detail.take(TaskRecord.MAX_DETAIL_LENGTH);s.phase=FieldPhase.RETURN
        s.reconcileWorld=false;s.reconcileCursor=0
        TaskInventory.resetRoute(e,npc);HarvestWorkClaims.kernel.release(npc.npcUuid)
        return NpcActionResult.running("field preparation stopped: $why; ${s.detail}")
    }
    private fun mismatch(record: TaskRecord,s: FieldPreparationState,detail: String): NpcActionResult {
        s.resources.uncertain=true;record.finish(TaskStatus.FAILED,TaskReason.STATE_MISMATCH,detail)
        return NpcActionResult.failed(record.detail)
    }
    private fun finish(record: TaskRecord,s: FieldPreparationState,complete: Boolean): NpcActionResult {
        s.phase=FieldPhase.DONE
        s.detail="field ${if(complete) "prepared" else "partial"}; confirmed=${s.confirmed.size}; stop=${s.stop}; ${s.detail}".take(TaskRecord.MAX_DETAIL_LENGTH)
        if (complete) record.completeActive(TaskReason.FIELD_PREPARED,s.detail)
        else record.finish(TaskStatus.FAILED,TaskReason.WORK_FAILED,s.detail)
        return if(complete) NpcActionResult.succeeded(s.detail) else NpcActionResult.failed(s.detail)
    }
    private fun above(soil: NpcBlockPosition)=NpcBlockPosition(soil.x,soil.y+1,soil.z)
}
