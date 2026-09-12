package io.samcnpc.behavior.task

import kotlin.math.abs

internal enum class ExplorerPhase { EXPLORE, RECOVER, BACKTRACK, RETURN_RECOVER, RETURN, DONE }
internal enum class ExplorerStop { CELL_LIMIT, FRONTIER_EXHAUSTED, RETURN_RESERVE }
internal data class ExplorerNode(val x: Int, val z: Int, val y: Double, val parent: Int, var tried: Int = 0)

internal class ExplorerTaskState(
    val nodes: MutableList<ExplorerNode>,
    var cursor: Int = 0,
    var phase: ExplorerPhase = ExplorerPhase.EXPLORE,
    var pending: ExplorerNode? = null,
    var stop: ExplorerStop? = null,
    var rejectedLegs: Int = 0,
    var lastFailure: String = "",
) {
    fun depth(): Int {
        var at=cursor
        var depth=0
        while (at > 0) { at=nodes[at].parent;depth++ }
        return depth
    }
    fun validationProblem(d: ExplorerTaskDefinition): String? {
        if (d.validationProblem() != null) return "invalid explorer definition"
        val limit=d.radius/d.cellStep
        if (nodes.size !in 1..d.maxCells || cursor !in nodes.indices) return "invalid explorer visited-cell count or cursor"
        val seen=hashSetOf<Pair<Int,Int>>()
        for ((index,node) in nodes.withIndex()) {
            if (node.x !in -limit..limit || node.z !in -limit..limit || node.tried !in 0..15 || !node.y.isFinite() || !d.routeBounds.contains(d.position(node))) return "explorer node is outside its bounds"
            if (!seen.add(node.x to node.z)) return "duplicate visited explorer cell"
            if (index == 0) {
                if (node.x != 0 || node.z != 0 || node.y != d.anchor.y || node.parent != -1) return "explorer root differs from its anchor"
            } else {
                if (node.parent !in 0 until index) return "explorer parent is not an earlier confirmed node"
                val parent=nodes[node.parent]
                val direction=direction(parent,node)
                if (direction == null || parent.tried and (1 shl direction) == 0 || abs(parent.y-node.y) > 1.5) return "explorer node lacks its adjacent attempted parent leg"
            }
        }
        val goal=pending
        if (goal != null) {
            if (phase != ExplorerPhase.EXPLORE || nodes.size >= d.maxCells || goal.parent != cursor || goal.tried != 0 ||
                goal.x !in -limit..limit || goal.z !in -limit..limit || (goal.x to goal.z) in seen || !goal.y.isFinite() || !d.routeBounds.contains(d.position(goal))) return "invalid pending explorer leg"
            val parent=nodes[cursor]
            val direction=direction(parent,goal)
            if (direction == null || parent.tried and (1 shl direction) == 0 || abs(parent.y-goal.y)>1.5) return "pending explorer leg is not an attempted neighbor"
        }
        if ((phase == ExplorerPhase.RETURN || phase == ExplorerPhase.RETURN_RECOVER || phase == ExplorerPhase.DONE) != (stop != null)) return "explorer return lacks its stop condition"
        if (stop == ExplorerStop.CELL_LIMIT && nodes.size != d.maxCells) return "explorer cell stop lacks its quota"
        if (stop == ExplorerStop.FRONTIER_EXHAUSTED && nodes.any { it.tried != 15 }) return "explorer frontier stop has untried directions"
        if (phase == ExplorerPhase.BACKTRACK && cursor == 0) return "explorer cannot backtrack above its anchor"
        if (phase == ExplorerPhase.DONE && (cursor != 0 || pending != null)) return "explorer completion is away from its anchor"
        if (rejectedLegs !in 0..256 || rejectedLegs > nodes.sumOf { Integer.bitCount(it.tried) }) return "explorer rejected-leg count lacks attempted edges"
        if (lastFailure.length > 256) return "explorer diagnostic exceeds its bound"
        return null
    }
    companion object {
        // Heading 0 is east, then north, west and south. World observations determine branches.
        val DX = intArrayOf(1,0,-1,0)
        val DZ = intArrayOf(0,-1,0,1)
        fun direction(a: ExplorerNode,b: ExplorerNode): Int? = (0..3).firstOrNull { b.x-a.x == DX[it] && b.z-a.z == DZ[it] }
        fun initial(d: ExplorerTaskDefinition) = ExplorerTaskState(mutableListOf(ExplorerNode(0,0,d.anchor.y,-1)))
    }
}
