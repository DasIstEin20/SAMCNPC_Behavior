package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcSnapshot
import net.minecraft.nbt.CompoundTag
import java.util.UUID

/** One real operation with an interruption at its existing operation-specific checkpoint. */
internal class ZooHourScene(val scene: OperationScene) {
    val family=ZooHourPlan.family(scene.kind)
    private var taskId: UUID?=null
    private var phase=0
    private var pausedTicks=0
    private var checkpointTicks=0
    private var frozen: CompoundTag?=null
    private val oldActions=mutableSetOf<UUID>()
    var complete=false
        private set
    val active get() = phase > 0 && !complete && scene.record.status in setOf(TaskStatus.RUNNING,TaskStatus.WAITING)
    fun assign() { check(phase == 0);OperationCases.prepare(scene);taskId=scene.record.id;phase=1 }
    /** True marks explicit fixture mutation so the profiler can exclude that tick. */
    fun tick(tick: Int): Boolean {
        if (complete) return false
        check(phase != 0 && scene.record.id == taskId)
        val record=scene.record
        check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "$family stopped: ${record.report()}" }
        if (phase == 1 && OperationCases.checkpoint(scene)) {
            oldActions.addAll(actions(scene.npc.snapshot()))
            check(TaskService.pause(scene.server,scene.npcId).status == NpcActionStatus.SUCCEEDED)
            scene.requireReleased();frozen=TaskCodec.write(record);checkpointTicks=tick;phase=2
            return true
        }
        if (phase == 2) {
            check(record.status == TaskStatus.PAUSED && TaskCodec.write(record) == frozen) { "$family changed its task while paused" }
            scene.requireReleased()
            if (++pausedTicks < 15) return false
            check(TaskService.resume(scene.server,scene.npcId).status == NpcActionStatus.SUCCEEDED)
            phase=3;return true
        }
        if (phase == 3) check(actions(scene.npc.snapshot()).none { it in oldActions }) { "$family reused a cancelled action" }
        if (record.status.terminal) {
            check(phase == 3 && pausedTicks == 15) { "$family finished without the required checkpoint interruption" }
            OperationCases.verify(scene);complete=true;return false
        }
        val growth=scene.kind == OperationKind.FARM && record.primary.farming?.phase == FarmPhase.WAIT_GROWTH
        OperationResourceCases.advanceWorldInput(scene)
        return growth
    }
    fun evidence() = linkedMapOf<String,Any>("family" to family,"kind" to scene.kind.name,"npc" to scene.npcId.toString(),
        "task" to taskId.toString(),"checkpointTick" to checkpointTicks,"pausedTicks" to pausedTicks,"cancelledHandles" to oldActions.size,
        "complete" to complete,"remainingTicks" to scene.record.primary.remainingTicks,"report" to scene.record.report().toString())
    private fun actions(s: NpcSnapshot)=listOfNotNull(s.navigation?.actionId,s.control?.actionId,s.blockBreak?.actionId,
        s.itemUse?.actionId,s.rangedAttack?.actionId,s.fishing?.actionId)
}
