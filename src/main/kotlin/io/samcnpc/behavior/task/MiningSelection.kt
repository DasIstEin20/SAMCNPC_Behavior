package io.samcnpc.behavior.task

import io.samcnpc.behavior.kernel.work.PlanningKind
import io.samcnpc.behavior.runtime.BehaviorPlanning
import io.samcnpc.core.api.*

internal enum class MiningProblem { UNOBSERVABLE, FLUID, FALLING_BLOCK, UNBREAKABLE, CONTAINER, DISALLOWED_BLOCK, NOT_EXPOSED, CHANGED, UNREACHABLE, MISSING_TOOL, INVENTORY_FULL, STORAGE, NO_RESOURCE, SELECTION_LIMIT }

/** Bounded durable selection intent, never a navigation path or live Core action. */
internal class MiningSelectionState {
    var limited=false
    var cursor=0
    var veinAnchor: NpcBlockPosition? = null
    val frontier=ArrayDeque<NpcBlockPosition>()
    val examined=linkedSetOf<NpcBlockPosition>()
    val cleared=linkedSetOf<NpcBlockPosition>()
    val removed=linkedMapOf<NpcBlockPosition,String>()
    val problems=linkedMapOf<MiningProblem,Int>()
    fun problem(value: MiningProblem) { problems[value]=((problems[value] ?: 0)+1).coerceAtMost(1_000_000) }
    fun confirmed(order: MiningWorkOrder, target: NpcBlockPosition, id: String) {
        require(order.area.contains(target) && order.canRemove(id))
        require(target !in removed && removed.size < MiningWorkOrder.MAX_REMOVED)
        removed[target]=id
        if (order.method == MiningMethod.TUNNEL || order.method == MiningMethod.EXCAVATION) cleared.add(target)
        if (order.method != MiningMethod.VEIN) return
        if (veinAnchor == null) veinAnchor=target
        for (next in MiningSelection.neighbors(target)) {
            if (!order.area.contains(next) || next in examined || next in frontier || next in removed) continue
            if (frontier.size+examined.size >= MiningWorkOrder.MAX_REMOVED) limited=true
            else frontier.addLast(next)
        }
    }
}

internal sealed interface MiningSelectionResult {
    data class Target(val position: NpcBlockPosition,val blockId: String) : MiningSelectionResult
    data class Stop(val reason: MiningProblem,val position: NpcBlockPosition?) : MiningSelectionResult
    data object Deferred : MiningSelectionResult
    data object Scanning : MiningSelectionResult
    data object Exhausted : MiningSelectionResult
}

/** Small current-world slices; a vein grows only from a physically confirmed removed member. */
internal object MiningSelection {
    private const val CELLS_PER_SLICE=16
    fun next(order: MiningWorkOrder,state: MiningSelectionState,world: NpcWorldView): MiningSelectionResult {
        if (order.method != MiningMethod.VEIN && state.cursor >= order.volume) return MiningSelectionResult.Exhausted
        if (state.removed.size >= MiningWorkOrder.MAX_REMOVED) return MiningSelectionResult.Stop(MiningProblem.SELECTION_LIMIT,null)
        if (!BehaviorPlanning.admit(world,CELLS_PER_SLICE*7,PlanningKind.GENERAL)) return MiningSelectionResult.Deferred
        var examined=0
        while (examined++ < CELLS_PER_SLICE) {
            val connected=order.method == MiningMethod.VEIN && state.veinAnchor != null
            val target=if (connected) state.frontier.removeFirstOrNull() ?: return if (state.limited) MiningSelectionResult.Stop(MiningProblem.SELECTION_LIMIT,null) else MiningSelectionResult.Exhausted
                else if (state.cursor < order.volume) order.cell(state.cursor++) else return MiningSelectionResult.Exhausted
            if (!order.area.contains(target) || target in state.removed || connected && !state.examined.add(target)) continue
            val block=world.observeBlockDetails(target)
            if (block == null || block.environment == null) return blocked(order,state,MiningProblem.UNOBSERVABLE,target)
            if (block.isAir) {
                if (order.method == MiningMethod.TUNNEL || order.method == MiningMethod.EXCAVATION) state.cleared.add(target)
                continue
            }
            if (!order.canRemove(block.blockId)) {
                if (order.method == MiningMethod.TUNNEL || order.method == MiningMethod.EXCAVATION) return blocked(order,state,MiningProblem.DISALLOWED_BLOCK,target)
                continue
            }
            val probe=probe(block,world)
            val problem=probe.problem
            if (problem != null) {
                val result=blocked(order,state,problem,target)
                if (result is MiningSelectionResult.Stop) return result
                continue
            }
            if (!connected && (order.method == MiningMethod.EXPOSED || order.method == MiningMethod.VEIN) &&
                !probe.exposed) {
                state.problem(MiningProblem.NOT_EXPOSED); continue
            }
            return MiningSelectionResult.Target(target,block.blockId)
        }
        return MiningSelectionResult.Scanning
    }
    private fun blocked(order: MiningWorkOrder,state: MiningSelectionState,problem: MiningProblem,target: NpcBlockPosition): MiningSelectionResult {
        state.problem(problem)
        return if (order.method == MiningMethod.TUNNEL || order.method == MiningMethod.EXCAVATION || problem == MiningProblem.UNOBSERVABLE)
            MiningSelectionResult.Stop(problem,target) else MiningSelectionResult.Scanning
    }
    private data class Probe(val problem: MiningProblem?,val exposed: Boolean = false)
    fun safety(block: NpcBlockObservation,world: NpcWorldView): MiningProblem? = probe(block,world).problem
    private fun probe(block: NpcBlockObservation,world: NpcWorldView): Probe {
        val facts=block.environment ?: return Probe(MiningProblem.UNOBSERVABLE)
        if (block.hasContainer) return Probe(MiningProblem.CONTAINER)
        if (facts.unbreakable) return Probe(MiningProblem.UNBREAKABLE)
        if (facts.fluidId != null) return Probe(MiningProblem.FLUID)
        if (facts.falling) return Probe(MiningProblem.FALLING_BLOCK)
        var exposed=false
        for (neighbor in neighbors(block.position)) {
            val observation=world.observeBlockDetails(neighbor) ?: return Probe(MiningProblem.UNOBSERVABLE)
            val adjacent=observation.environment ?: return Probe(MiningProblem.UNOBSERVABLE)
            if (adjacent.fluidId != null) return Probe(MiningProblem.FLUID)
            if (neighbor.y > block.position.y && adjacent.falling) return Probe(MiningProblem.FALLING_BLOCK)
            if (observation.isAir) exposed=true
        }
        return Probe(null,exposed)
    }
    fun neighbors(p: NpcBlockPosition): List<NpcBlockPosition> = listOf(
        NpcBlockPosition(p.x-1,p.y,p.z),NpcBlockPosition(p.x+1,p.y,p.z),NpcBlockPosition(p.x,p.y-1,p.z),
        NpcBlockPosition(p.x,p.y+1,p.z),NpcBlockPosition(p.x,p.y,p.z-1),NpcBlockPosition(p.x,p.y,p.z+1))
}
