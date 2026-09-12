package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Loaded site facts only. Native placement still validates its exact carried sapling afterwards. */
internal object PlantingSites {
    data class Observation(val initial: Set<NpcBlockPosition> = emptySet(),val problem: PlantingProblem? = null,val detail: String = "")
    private val soils=setOf("minecraft:dirt","minecraft:grass_block","minecraft:coarse_dirt","minecraft:podzol","minecraft:rooted_dirt","minecraft:mycelium","minecraft:moss_block","minecraft:mud","minecraft:muddy_mangrove_roots")
    fun cost(species: SaplingSpecies): Int {
        val width=species.layoutSize+2*species.clearanceMargin
        return width*width*species.clearanceHeight+species.layoutSize*species.layoutSize*3
    }
    fun observe(world: NpcWorldView,w: PlantingWorkOrder,base: NpcBlockPosition,pending: PlantingPlot? = null): Observation {
        val footprint=w.species.footprint(base)
        val initial=linkedSetOf<NpcBlockPosition>(); var logs=0
        for (position in footprint) {
            val block=world.observeBlockDetails(position) ?: return Observation(problem=PlantingProblem.UNOBSERVABLE,detail="layout member is unavailable at $position")
            when {
                block.blockId == w.species.blockId -> initial.add(position)
                block.blockId == w.species.logId -> logs++
                !block.isAir || block.environment?.fluidId != null -> return Observation(problem=PlantingProblem.OCCUPIED,detail="layout member contains ${block.blockId} at $position")
            }
        }
        if (logs > 0) return Observation(problem=if (logs == footprint.size) PlantingProblem.GROWN_TREE else PlantingProblem.OCCUPIED,detail="matching trunk occupies $logs/${footprint.size} layout cells")
        if (initial.size == footprint.size && pending == null) return Observation(initial,PlantingProblem.ALREADY_PLANTED,"matching sapling layout already exists")
        if (pending == null && w.mode == PlantingMode.PATCH && initial.isNotEmpty()) return Observation(initial,PlantingProblem.OCCUPIED,"patch mode requires a fully empty layout")
        if (pending != null && initial != pending.initial+pending.placed) return Observation(initial,PlantingProblem.CHANGED,"pending layout differs from its actual placement journal")
        for (position in footprint) {
            val soil=world.observeBlockDetails(NpcBlockPosition(position.x,position.y-1,position.z)) ?: return Observation(problem=PlantingProblem.UNOBSERVABLE,detail="layout soil is unavailable")
            if (soil.blockId !in soils || soil.environment?.fluidId != null) return Observation(problem=PlantingProblem.INVALID_SOIL,detail="unsupported sapling soil ${soil.blockId} at $position")
        }
        val margin=w.species.clearanceMargin
        for (y in 0 until w.species.clearanceHeight) for (z in -margin until w.species.layoutSize+margin) for (x in -margin until w.species.layoutSize+margin) {
            val position=NpcBlockPosition(base.x+x,base.y+y,base.z+z)
            if (position in footprint) continue
            val block=world.observeBlock(position) ?: return Observation(problem=PlantingProblem.UNOBSERVABLE,detail="planting clearance is unavailable")
            // Existing canopy is retained; planting does not promise or accelerate tree growth.
            if (!block.isAir && block.blockId != w.species.leafId) return Observation(problem=PlantingProblem.OBSTRUCTED,detail="supplied planting clearance contains ${block.blockId} at $position")
        }
        return Observation(java.util.Set.copyOf(initial))
    }
}
