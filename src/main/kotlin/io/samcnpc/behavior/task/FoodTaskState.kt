package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import java.util.UUID

internal enum class FoodPhase { SELECT, BERRY, HUNT, COLLECT, WITHDRAW, DEPOSIT, RETURN }
internal enum class FoodProblem { NO_FOOD, UNOBSERVABLE, UNREACHABLE, CHANGED, INVENTORY_FULL, HUNT_LIMIT, HUNT_ENDED, OBSERVATION_LIMIT }
internal class FoodTaskState(resources: ProducedResources) : ProducedCargoState(resources) {
    var phase = FoodPhase.SELECT
    var cursor = 0
    var berry: NpcBlockPosition? = null
    var collectionOrigin: NpcPosition? = null
    var collectionTicks = 0
    var exhausted = false
    var stop: FoodProblem? = null
    var selectedSource: NpcBlockPosition? = null
    val sourceCheckpoints = linkedMapOf<NpcBlockPosition,ContainerCheckpoint>()
    val withdrawals = linkedMapOf<NpcBlockPosition,Map<String,Int>>()
    val knownFood = linkedSetOf<String>()
    val pickups = linkedMapOf<String,Int>()
    val harvested = linkedSetOf<NpcBlockPosition>()
    var huntTarget: UUID? = null
    var huntFrame: UUID? = null
    val hunts = linkedMapOf<UUID,Int>()
    fun foodOutput() = knownFood.sumOf(resources::available)
    fun retainedFood() = knownFood.sumOf { resources.physical.entries[it]?.retained ?: 0 }
    fun deliverable(d: FoodTaskDefinition): Int = minOf(foodOutput(), (retainedFood()-d.keepFood).coerceAtLeast(0))
    fun goal(d: FoodTaskDefinition) = delivered(d) >= d.quantity && retainedFood() >= d.keepFood
    fun enough(d: FoodTaskDefinition) = delivered(d)+deliverable(d) >= d.quantity && retainedFood() >= d.keepFood
    companion object { const val COLLECTION_TICKS = 80; const val DROP_WAIT = 20; const val MAX_BUSHES = 512 }
}
