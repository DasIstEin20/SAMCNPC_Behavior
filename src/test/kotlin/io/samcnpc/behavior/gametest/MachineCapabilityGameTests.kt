package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.capabilities.ICapabilityProvider
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.event.AttachCapabilitiesEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.ItemStackHandler

@GameTestHolder("samcnpc_machine_zoo")
@PrefixGameTestTemplate(false)
object MachineCapabilityGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1200,batch="machine_partial_capability")
    fun theSameTaskUsesSidedActualPartialResultsWithoutAnyMachineClassBranch(h: GameTestHelper) {
        val arena=CombatGameTestArena(h);val at=h.absolutePos(BlockPos(8,1,0))
        val provider=Provider(at);MinecraftForge.EVENT_BUS.register(provider)
        h.level.setBlock(at,Blocks.ENCHANTING_TABLE.defaultBlockState(),3)
        check(provider.attached)
        val endpoint=NpcContainerEndpoint(h.level.dimension().location().toString(),NpcBlockPosition(at.x,at.y,at.z))
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.DIAMOND,7))
            arena.assign(npc,MachineTaskDefinition(endpoint.dimensionId,MachineFeeds(listOf(MachinePort(endpoint.copy(side=NpcBlockFace.UP),0,"minecraft:diamond",4))),
                MachinePort(endpoint.copy(side=NpcBlockFace.DOWN),0,"minecraft:emerald",2),arena.start,returnTo=arena.start,pollTicks=5,budget=TaskBudget(1200)))
        }
        arena.observe { npc,record ->
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED) { record.report() }
                val state=checkNotNull(record.primary.machine)
                check(state.supplied.single() == 4 && state.collected == 2 && state.transfers == 6)
                check(provider.store.getStackInSlot(0).count == 4 && provider.store.getStackInSlot(1).isEmpty)
                check(TaskDelivery.inventoryCount(npc,"minecraft:diamond") == 3 && TaskDelivery.inventoryCount(npc,"minecraft:emerald") == 2)
                check(provider.input.actualCalls == 4 && provider.output.actualCalls == 2)
                MinecraftForge.EVENT_BUS.unregister(provider)
                com.mojang.logging.LogUtils.getLogger().info("MACHINE_PARTIAL_PROVIDER_PROOF inputs=4 outputs=2 actual_transfers=6 initial_output_fixture=true")
                arena.succeed(npc,record)
            }
        }
    }
    private class Provider(private val at: BlockPos) {
        val store=ItemStackHandler(2)
        val input=Port(store,0,true)
        val output=Port(store,1,false)
        private val top=LazyOptional.of<IItemHandler> { input }
        private val bottom=LazyOptional.of<IItemHandler> { output }
        var attached=false
        init { store.setStackInSlot(1,ItemStack(Items.EMERALD,2)) }
        @SubscribeEvent fun attach(event: AttachCapabilitiesEvent<BlockEntity>) {
            if (event.`object`.blockPos != at || !event.`object`.blockState.`is`(Blocks.ENCHANTING_TABLE)) return
            check(!attached);attached=true
            event.addCapability(ResourceLocation.fromNamespaceAndPath("samcnpc_behavior","machine_partial_probe"),object : ICapabilityProvider {
                override fun <T : Any?> getCapability(cap: Capability<T>,side: Direction?): LazyOptional<T> {
                    if (cap != ForgeCapabilities.ITEM_HANDLER) return LazyOptional.empty()
                    return when (side) { Direction.UP -> top.cast();Direction.DOWN -> bottom.cast();else -> LazyOptional.empty() }
                }
            })
            event.addListener { top.invalidate();bottom.invalidate() }
        }
    }
    /** The fixture advertises capacity but each actual transfer moves one real stored item. */
    private class Port(private val store: ItemStackHandler,private val index: Int,private val input: Boolean) : IItemHandler {
        var actualCalls=0
        override fun getSlots()=1
        override fun getStackInSlot(slot: Int): ItemStack { require(slot == 0);return store.getStackInSlot(index) }
        override fun getSlotLimit(slot: Int): Int { require(slot == 0);return 64 }
        override fun isItemValid(slot: Int,stack: ItemStack): Boolean = input && slot == 0 && stack.`is`(Items.DIAMOND)
        override fun insertItem(slot: Int,stack: ItemStack,simulate: Boolean): ItemStack {
            require(slot == 0)
            if (!isItemValid(slot,stack)) return stack
            if (simulate) return store.insertItem(index,stack,true)
            actualCalls++
            val one=stack.copy();one.count=minOf(1,stack.count)
            val remainder=store.insertItem(index,one,false)
            val result=stack.copy();result.shrink(one.count-remainder.count);return result
        }
        override fun extractItem(slot: Int,amount: Int,simulate: Boolean): ItemStack {
            require(slot == 0)
            if (input) return ItemStack.EMPTY
            if (!simulate) actualCalls++
            return store.extractItem(index,if (simulate) amount else minOf(1,amount),simulate)
        }
    }
}
