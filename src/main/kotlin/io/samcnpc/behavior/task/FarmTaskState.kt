package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import java.util.UUID

internal enum class FarmPhase { PREPARE, SELECT, HARVEST, COLLECT, SOIL, PLANT, SUPPLY, WAIT_GROWTH, DEPOSIT, RETURN }
internal enum class FarmProblem { UNOBSERVABLE, FOREIGN_BLOCK, CHANGED, UNREACHABLE, MISSING_HOE, MISSING_SEEDS, INVALID_SOIL, GROWTH_TIMEOUT, INVENTORY_FULL, SUPPLY_ENDED, OBSERVATION_LIMIT }
internal class FarmTaskState(resources: ProducedResources,d: FarmTaskDefinition) : ProducedCargoState(resources) {
    var phase=if (d.work.mode == FarmMode.CULTIVATE) FarmPhase.PREPARE else FarmPhase.SELECT
    var cursor=0
    var cycle=0
    var target: NpcBlockPosition? = null
    var preparing=false
    var collectionTicks=0
    var growthRemaining=d.work.growthWaitTicks
    var nextGrowthCheck=0
    var exhausted=false
    var stop: FarmProblem? = null
    var stopDetail: String? = null
    var reconcileWorld=false
    var reconcileCursor=0
    var supplyFrame: UUID? = null
    val cycleDone=linkedSetOf<NpcBlockPosition>()
    val harvested=linkedMapOf<NpcBlockPosition,Int>()
    val planted=linkedMapOf<NpcBlockPosition,Int>()
    val replanted=linkedMapOf<NpcBlockPosition,Int>()
    val tilled=linkedMapOf<NpcBlockPosition,Int>()
    val pickups=linkedMapOf<String,Int>()
    fun totalHarvests()=harvested.values.sum()
    fun reserve(d: FarmTaskDefinition): Int {
        if (d.work.mode == FarmMode.HARVEST) return 0
        // Pending replant is a real obligation even after the requested yield is available.
        val pending=if (target != null && !preparing && phase in setOf(FarmPhase.HARVEST,FarmPhase.COLLECT,FarmPhase.SOIL,FarmPhase.PLANT,FarmPhase.SUPPLY)) 1 else 0
        return d.work.keepSeeds+pending
    }
    fun deliverable(d: FarmTaskDefinition): Int {
        val item=d.work.crop.itemId
        val retained=resources.physical.entries[item]?.retained ?: 0
        val floor=if (item == d.work.crop.seedId) reserve(d) else 0
        return minOf(resources.available(item),(retained-floor).coerceAtLeast(0))
    }
    fun goal(d: FarmTaskDefinition) = delivered(d) >= d.quantity && totalHarvests() > 0 && target == null && cycle >= 1 &&
        (d.work.mode == FarmMode.HARVEST || harvested.all { (p,count) -> (replanted[p] ?: 0) >= count })
    companion object { const val COLLECTION_TICKS=80; const val DROP_WAIT=20 }
}
