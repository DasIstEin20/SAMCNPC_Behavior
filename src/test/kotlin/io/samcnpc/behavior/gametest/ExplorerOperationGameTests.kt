package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@GameTestHolder("samcnpc_explorer_zoo")
@PrefixGameTestTemplate(false)
object ExplorerOperationGameTests {
    private enum class Incident { NORMAL, ENCLOSED, STALE_BLOCK, WATER, FALL, PAUSE_COMBAT, RETURN_BLOCKED, RESERVE, CANCEL, OUTSIDE }
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=2400,batch="explorer_normal")
    fun visitedCellsArePhysicalAndReturnUsesTheConfirmedTrail(h: GameTestHelper)=explore(h,Incident.NORMAL)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=600,batch="explorer_enclosed")
    fun impossibleFrontierEndsAtAnchorWithoutInventingVisits(h: GameTestHelper)=explore(h,Incident.ENCLOSED)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=2400,batch="explorer_stale")
    fun obstacleAddedAfterSelectionRejectsTheLegAndUsesAnotherBranch(h: GameTestHelper)=explore(h,Incident.STALE_BLOCK)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=2400,batch="explorer_water")
    fun waterCellIsRejectedFromFreshStandingObservations(h: GameTestHelper)=explore(h,Incident.WATER)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=2400,batch="explorer_fall")
    fun physicalFallRecoversTheWaypointAndRetainsTheOriginalTask(h: GameTestHelper)=explore(h,Incident.FALL)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=2400,batch="explorer_combat")
    fun pauseAndCombatReleaseTheOldRouteAndResumeTheVisitedTrail(h: GameTestHelper)=explore(h,Incident.PAUSE_COMBAT)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=2400,batch="explorer_blocked_return")
    fun removedReturnStanceFailsWithinTheOriginalRetryBudget(h: GameTestHelper)=explore(h,Incident.RETURN_BLOCKED)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=900,batch="explorer_reserve")
    fun returnReserveStopsExplorationBeforeTheOriginalDeadline(h: GameTestHelper)=explore(h,Incident.RESERVE)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=700,batch="explorer_cancel")
    fun cancelledExpeditionDoesNotKeepMovingOrAddingVisitedCells(h: GameTestHelper)=explore(h,Incident.CANCEL)
    @JvmStatic @GameTest(template="npc_explorer_field",timeoutTicks=700,batch="explorer_outside")
    fun externalDisplacementOutsideBoundsFailsWithoutTeleportingHome(h: GameTestHelper)=explore(h,Incident.OUTSIDE)

    private fun explore(h: GameTestHelper,incident: Incident) {
        val a=ExplorerGameTestArena(h)
        val cells=if (incident == Incident.RESERVE) 64 else 6
        val ticks=if (incident == Incident.RESERVE) 500 else 2200
        var original: UUID?=null
        var remaining=ticks
        var changed=false
        var observedRecovery=false
        var observedAir=false
        var pausedTicks=0
        var frozen=0
        var combat=false
        var terminalTicks=0
        var frozenNodes: List<ExplorerNode>?=null
        var previousNodes=emptyList<ExplorerNode>()
        var previousPosition: NpcPosition?=null
        a.run(setup={
            if (incident == Incident.ENCLOSED) for (direction in 0..3) a.blockCell(ExplorerTaskState.DX[direction],ExplorerTaskState.DZ[direction])
            if (incident == Incident.WATER) a.floodCell(1,0)
            a.assign(cells,ticks)
        }) { r ->
            val d=r.primary.definition as ExplorerTaskDefinition
            val s=checkNotNull(r.primary.explorer)
            if (original == null) original=r.id
            check(r.id == original && r.primary.remainingTicks <= remaining)
            remaining=r.primary.remainingTicks
            check(TaskDelivery.inventoryCount(a.npc,"minecraft:diamond") == 7)
            check(s.validationProblem(d) == null) { s.validationProblem(d).orEmpty() }
            check(s.nodes.size >= previousNodes.size && s.nodes.take(previousNodes.size).map { it.copy(tried=0) } == previousNodes.map { it.copy(tried=0) })
            if (s.nodes.size > previousNodes.size) {
                val last=d.position(s.nodes.last())
                check(a.body.onGround() && TaskNavigator.distanceSquared(a.npc.snapshot().position,last) <= 0.75*0.75) { "unvisited cell recorded" }
                previousNodes=s.nodes.map { it.copy() }
            }
            if (incident != Incident.OUTSIDE) check(d.bounds.contains(a.npc.snapshot().position))
            if (s.phase == ExplorerPhase.RECOVER) observedRecovery=true
            if (changed && !a.body.onGround()) observedAir=true
            if (!r.status.terminal) when (incident) {
                Incident.STALE_BLOCK -> if (!changed && s.pending != null) { val pending=checkNotNull(s.pending);a.blockCell(pending.x,pending.z);changed=true }
                Incident.FALL -> if (!changed && s.nodes.size == 2 && s.pending == null) {
                    a.npc.stopControl();a.body.moveTo(a.body.x,a.body.y+3.2,a.body.z+2.0);changed=true
                }
                Incident.PAUSE_COMBAT -> {
                    if (!changed && s.nodes.size == 2 && s.pending != null) {
                        check(TaskService.pause(a.server,a.body.uuid).status == NpcActionStatus.SUCCEEDED)
                        frozen=r.primary.remainingTicks;frozenNodes=s.nodes.map { it.copy() };changed=true;a.released()
                    }
                    if (r.status == TaskStatus.PAUSED) {
                        check(r.primary.remainingTicks == frozen && s.nodes == frozenNodes)
                        if (++pausedTicks == 30) check(TaskService.resume(a.server,a.body.uuid).status == NpcActionStatus.SUCCEEDED)
                    } else if (changed && !combat) { a.interruptWithCombat();combat=true }
                    if (r.frames.size > 1) check(s.nodes == frozenNodes)
                }
                Incident.RETURN_BLOCKED -> if (!changed && s.nodes.size == cells) { a.blockCell(0,0);changed=true }
                Incident.CANCEL -> if (!changed && s.pending != null && a.npc.snapshot().navigation != null) {
                    check(TaskService.cancel(a.server,a.body.uuid).status == NpcActionStatus.SUCCEEDED);changed=true;frozenNodes=s.nodes.map { it.copy() }
                }
                Incident.OUTSIDE -> if (!changed && s.nodes.size == 2) { a.body.moveTo(a.start.x+17.0,a.start.y,a.start.z);changed=true }
                else -> Unit
            }
            if (!r.status.terminal) return@run
            when (incident) {
                Incident.RETURN_BLOCKED -> check(changed && r.status == TaskStatus.FAILED && r.reason == TaskReason.RETRY_LIMIT && r.totalFailures == d.budget.attempts && s.cursor != 0)
                Incident.OUTSIDE -> check(changed && r.status == TaskStatus.FAILED && r.reason == TaskReason.WORK_FAILED && a.body.x > d.bounds.max.x)
                Incident.CANCEL -> {
                    check(changed && r.status == TaskStatus.CANCELLED && r.reason == TaskReason.USER_CANCELLED && s.nodes == frozenNodes)
                    a.released();terminalTicks++
                    if (terminalTicks == 20) previousPosition=a.npc.snapshot().position
                    if (terminalTicks >= 21) check(TaskNavigator.distanceSquared(a.npc.snapshot().position,checkNotNull(previousPosition)) < 0.01)
                    if (terminalTicks < 70) return@run
                }
                else -> {
                    check(r.status == TaskStatus.COMPLETED && r.reason == TaskReason.EXPLORATION_FINISHED) { r.report() }
                    check(s.phase == ExplorerPhase.DONE && s.cursor == 0 && a.body.onGround())
                    check(TaskNavigator.distanceSquared(a.npc.snapshot().position,a.start) <= 0.75*0.75)
                    when (incident) {
                        Incident.ENCLOSED -> check(s.nodes.size == 1 && s.stop == ExplorerStop.FRONTIER_EXHAUSTED && s.rejectedLegs == 4)
                        Incident.RESERVE -> check(s.nodes.size in 2 until cells && s.stop == ExplorerStop.RETURN_RESERVE && r.primary.remainingTicks > 0)
                        else -> check(s.nodes.size == cells && s.stop == ExplorerStop.CELL_LIMIT)
                    }
                    if (incident == Incident.STALE_BLOCK || incident == Incident.WATER) check(s.rejectedLegs >= 1 && s.nodes.none { it.x == 1 && it.z == 0 })
                    if (incident == Incident.FALL) check(changed && observedRecovery && observedAir)
                    if (incident == Incident.PAUSE_COMBAT) check(combat && pausedTicks == 30 && r.completedInterruptions == 1 && r.lastCombat?.reason == TaskReason.TARGET_DEFEATED)
                }
            }
            a.pass(r)
        }
    }
}
