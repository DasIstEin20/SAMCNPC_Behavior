package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition

/** Literal registered IDs, compiled once; work never interprets tags/classes/commands from strings. */
internal class WorkResourceIds(ids: List<String>) {
    val values: List<String> = java.util.List.copyOf(ids)
    private val lookup: Set<String> = java.util.Set.copyOf(ids)
    init {
        require(values.size in 1..64 && values.size == lookup.size) { "resource filter must contain 1..64 unique IDs" }
        require(values.all { it.length <= 256 && ID.matches(it) }) { "invalid namespaced resource ID" }
    }
    fun matches(id: String): Boolean = id in lookup
    override fun equals(other: Any?): Boolean = other is WorkResourceIds && values == other.values
    override fun hashCode(): Int = values.hashCode()
    override fun toString(): String = values.toString()
    companion object { private val ID=Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$") }
}

internal enum class MiningMethod { EXPOSED, VEIN, TUNNEL, EXCAVATION }
internal enum class MiningCounting { DELIVERED_ITEMS, REMOVED_RESOURCE_BLOCKS, CLEARED_VOLUME }
internal enum class TunnelDirection(val x: Int,val z: Int) { EAST(1,0), WEST(-1,0), SOUTH(0,1), NORTH(0,-1) }

/** Desired resources and authorized access removals are deliberately separate filters. */
internal data class MiningWorkOrder(
    val area: WorkArea,
    val method: MiningMethod,
    val resources: WorkResourceIds,
    val access: WorkResourceIds? = null,
    val tunnel: TunnelGeometry? = null,
) {
    val volume: Int get() = if (method == MiningMethod.TUNNEL) tunnel?.volume ?: 0
        else area.bounds.width*area.bounds.height*area.bounds.depth
    fun validationProblem(): String? {
        area.validationProblem()?.let { return it }
        if ((method == MiningMethod.EXPOSED || method == MiningMethod.VEIN) && access != null) return "exposed/vein work cannot authorize unrelated access removal"
        if (method == MiningMethod.TUNNEL) {
            val geometry=tunnel ?: return "tunnel geometry is required"
            geometry.validationProblem()?.let { return it }
            if (geometry.bounds() != area.bounds || area.exclusions.isNotEmpty()) return "tunnel work area must match its exact geometry without holes"
        } else if (tunnel != null) return "non-tunnel work cannot carry tunnel geometry"
        if (volume !in 1..MAX_SCAN_CELLS) return "mining scan volume must be1..$MAX_SCAN_CELLS cells"
        if ((method == MiningMethod.TUNNEL || method == MiningMethod.EXCAVATION) && volume > MAX_REMOVED) return "excavation volume exceeds$MAX_REMOVED cells"
        return null
    }
    fun canRemove(id: String): Boolean = resources.matches(id) || access?.matches(id) == true
    fun contains(position: NpcBlockPosition): Boolean = area.contains(position) && tunnel?.contains(position) != false
    fun cell(index: Int): NpcBlockPosition {
        require(index in 0 until volume)
        val geometry=tunnel
        if (geometry != null) return geometry.cell(index)
        val bounds=area.bounds; val plane=bounds.width*bounds.depth
        return NpcBlockPosition(bounds.min.x+index%plane%bounds.width,bounds.max.y-index/plane,bounds.min.z+index%plane/bounds.width)
    }
    companion object { const val MAX_SCAN_CELLS=32768; const val MAX_REMOVED=4096 }
}
