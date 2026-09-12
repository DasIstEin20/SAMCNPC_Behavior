package io.samcnpc.behavior.operations

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.NpcFishingPhase
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks

internal object OperationFishingCase {
    val kinds = setOf(OperationKind.FISHING_WAIT,OperationKind.FISHING_COLLECT)
    fun prepare(s: OperationScene) {
        for (x in 2..14) for (z in -4..4) {
            s.level.setBlock(s.pos(x,-2,z),Blocks.STONE.defaultBlockState(),3)
            for (y in -1..0) s.level.setBlock(s.pos(x,y,z),Blocks.WATER.defaultBlockState(),3)
        }
        val rod=ItemStack(Items.FISHING_ROD)
        rod.enchant(Enchantments.FISHING_SPEED,3)
        s.give(rod);s.give(Items.DIAMOND,7)
        s.assign(FishingTaskDefinition(s.npc.snapshot().dimensionId,s.block(7,0,0),s.start,2,s.start,budget=TaskBudget(3000)))
    }
    fun checkpoint(s: OperationScene): Boolean {
        val state=checkNotNull(s.record.primary.fishing)
        return if (s.kind == OperationKind.FISHING_WAIT) {
            state.caught == 0 && state.phase == FishingPhase.WAIT && s.npc.fishingState()?.phase in
                setOf(NpcFishingPhase.WAITING,NpcFishingPhase.APPROACHING)
        } else state.caught == 1 && state.phase == FishingPhase.COLLECT && state.drops.isNotEmpty()
    }
    fun verify(s: OperationScene) {
        s.requireCompleted();s.requireReturned()
        val state=checkNotNull(s.record.primary.fishing)
        check(state.caught == 2 && state.collectedCatches == 2 && state.drops.isEmpty() && !state.pendingReel)
        check(state.spawned.isNotEmpty() && state.spawned.all { (id,count) ->
            (state.resources.entries[id]?.gathered ?: 0) >= count && s.carried(id) >= count
        })
        check(s.carried("minecraft:diamond") == 7)
        val rod=s.npc.inventoryContents().first { it.slot == 0 && it.stack.itemId == FishingTaskDefinition.ROD }.stack
        check(rod.damage == 2)
        val expectedCasts=if (s.kind == OperationKind.FISHING_WAIT) 3 else 2
        check(state.casts == expectedCasts) { "${s.kind} replayed or skipped an actual cast: ${state.casts}" }
    }
}
