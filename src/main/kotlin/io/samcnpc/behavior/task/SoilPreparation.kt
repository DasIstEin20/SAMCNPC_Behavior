package io.samcnpc.behavior.task

import io.samcnpc.core.api.*

/** Shared physical hoe use; each caller owns its area, claims, approach and completion policy. */
internal object SoilPreparation {
    val tillable=setOf("minecraft:dirt","minecraft:grass_block","minecraft:dirt_path")
    fun carriedHoe(npc: NpcFacade): NpcInventoryEntry? = npc.inventoryContents().firstOrNull {
        !it.stack.isEmpty && it.knowledge.toolKind==NpcToolKind.HOE
    }

    sealed interface Result {
        data class EquipRejected(val action: NpcActionResult): Result
        data class Used(val action: NpcActionResult,val itemId: String,val before: Map<String,Int>,
            val after: Map<String,Int>,val farmland: Boolean): Result {
            fun consumptionProblem(): String? {
                val consumed=(before[itemId] ?: 0)-(after[itemId] ?: 0)
                return if (consumed !in 0..1 || before.any { (id,count) -> id!=itemId && (after[id] ?: 0)<count })
                    "hoe use changed inventory beyond one carried tool consumption" else null
            }
        }
    }

    fun use(npc: NpcFacade,world: NpcWorldView,soil: NpcBlockPosition,hoe: NpcInventoryEntry): Result {
        val equip=npc.equipFromInventory(hoe.slot,NpcEquipmentDestination.MAIN_HAND)
        if (equip.status!=NpcActionStatus.SUCCEEDED) return Result.EquipRejected(equip)
        val item=checkNotNull(hoe.stack.itemId)
        val before=HarvestResources.inventoryCounts(npc)
        val eye=npc.snapshot().eyePosition
        val direction=NpcVector(soil.x+0.5-eye.x,soil.y+0.9-eye.y,soil.z+0.5-eye.z)
        val distance=kotlin.math.sqrt(direction.x*direction.x+direction.y*direction.y+direction.z*direction.z)
        // An invented inward offset is not the surface hit, especially at a shallow angle.
        // Core revalidates this observed outline hit immediately before the native callback.
        val hit=world.raycast(NpcRaycastRequest(eye,direction,(distance+0.02).coerceAtMost(NpcRaycastRequest.MAX_DISTANCE)))
        val action=if(hit is NpcRaycastResult.BlockHit && hit.position==soil && hit.face!=NpcBlockFace.DOWN)
            npc.useItemOnBlock(NpcBlockHit(soil,hit.face,hit.location),NpcHand.MAIN)
        else NpcActionResult.rejected("authorized soil has no visible usable surface hit")
        return Result.Used(action,item,before,HarvestResources.inventoryCounts(npc),world.observeBlock(soil)?.blockId=="minecraft:farmland")
    }
}
