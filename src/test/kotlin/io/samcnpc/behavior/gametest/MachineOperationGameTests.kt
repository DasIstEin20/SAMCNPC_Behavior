package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.Container
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder("samcnpc_machine_zoo")
@PrefixGameTestTemplate(false)
object MachineOperationGameTests {
    private enum class Incident { NORMAL, NO_FUEL, OUTPUT_BLOCKED, NPC_FULL, REMOVAL, PAUSE_COMBAT }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="machine_normal")
    fun vanillaFurnaceProducesActualOutputFromSuppliedOreAndFuel(h: GameTestHelper) = furnace(h,Incident.NORMAL)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1000,batch="machine_no_fuel")
    fun noFuelEndsWithObservedNoProgressAndPreservesTheSuppliedOre(h: GameTestHelper) = furnace(h,Incident.NO_FUEL)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="machine_output_blocked")
    fun foreignFullOutputCanBeClearedWithoutReplayingTheInput(h: GameTestHelper) = furnace(h,Incident.OUTPUT_BLOCKED)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1800,batch="machine_npc_full")
    fun fullCarriedInventoryWaitsUntilAnActualExternalTransferMakesRoom(h: GameTestHelper) = furnace(h,Incident.NPC_FULL)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=800,batch="machine_removed")
    fun removedMachineLeavesNativeDropsAndAnExplicitPartialReport(h: GameTestHelper) = furnace(h,Incident.REMOVAL)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="machine_pause_combat")
    fun pausedMachineSurvivesARealCombatInterruptionWithoutRefillingItsInputs(h: GameTestHelper) = furnace(h,Incident.PAUSE_COMBAT)

    private fun furnace(h: GameTestHelper,incident: Incident) {
        val arena=CombatGameTestArena(h);val server=h.level.server
        val at=h.absolutePos(BlockPos(8,1,0));h.level.setBlock(at,Blocks.FURNACE.defaultBlockState(),3)
        val machine=checkNotNull(h.level.getBlockEntity(at) as? Container)
        val storageAt=h.absolutePos(BlockPos(7,1,3));h.level.setBlock(storageAt,Blocks.CHEST.defaultBlockState(),3)
        val storage=checkNotNull(h.level.getBlockEntity(storageAt) as? Container)
        if (incident == Incident.OUTPUT_BLOCKED) machine.setItem(2,ItemStack(Items.COPPER_INGOT,64))
        val quantity=if (incident == Incident.NPC_FULL) 1 else 4
        val endpoint=NpcContainerEndpoint(h.level.dimension().location().toString(),NpcBlockPosition(at.x,at.y,at.z))
        val feed=MachinePort(endpoint.copy(side=NpcBlockFace.UP),0,"minecraft:raw_iron",quantity)
        val fuel=MachinePort(endpoint.copy(side=NpcBlockFace.NORTH),0,"minecraft:coal",1)
        val definition=MachineTaskDefinition(endpoint.dimensionId,MachineFeeds(if (incident == Incident.NO_FUEL) listOf(feed) else listOf(feed,fuel)),
            MachinePort(endpoint.copy(side=NpcBlockFace.DOWN),0,"minecraft:iron_ingot",quantity),arena.start,returnTo=arena.start,
            pollTicks=10,noProgressTicks=if (incident == Incident.NO_FUEL) 120 else 1200,budget=TaskBudget(2200))
        var original: java.util.UUID?=null;var remaining=2200;var intervention=false;var waited=0;var pauseTicks=0
        var pausedRemaining=0;var pausedFeeds=emptyList<Int>();var enemy: net.minecraft.world.entity.LivingEntity?=null
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.RAW_IRON,if (incident == Incident.NPC_FULL) 64 else quantity))
            if (incident != Incident.NO_FUEL) arena.give(npc,ItemStack(Items.COAL,if (incident == Incident.NPC_FULL) 64 else 1))
            if (incident == Incident.NPC_FULL) repeat(34) { arena.give(npc,ItemStack(Items.DIRT,64)) }
            if (incident == Incident.PAUSE_COMBAT) arena.give(npc,ItemStack(Items.IRON_SWORD))
            arena.assign(npc,definition)
        }
        arena.observe { npc,record ->
            if (original == null) original=record.id
            check(record.id == original && record.primary.remainingTicks <= remaining)
            remaining=record.primary.remainingTicks
            val state=checkNotNull(record.primary.machine)
            if (!record.status.terminal) when (incident) {
                Incident.OUTPUT_BLOCKED -> if (!intervention && state.supplied.all { it > 0 } && ++waited >= 100) {
                    check(state.collected == 0 && machine.getItem(2).`is`(Items.COPPER_INGOT) && machine.getItem(0).count == quantity)
                    storage.setItem(0,machine.removeItem(2,64));storage.setChanged();machine.setChanged();intervention=true
                }
                Incident.NPC_FULL -> if (!intervention && machine.getItem(2).`is`(Items.IRON_INGOT) && ++waited >= 40) {
                    check(state.collected == 0 && npc.inventoryContents().none { it.stack.isEmpty })
                    val dirt=npc.inventoryContents().first { it.stack.itemId == "minecraft:dirt" }
                    val transfer=npc.transferToContainer(dirt.slot,NpcContainerTransferRequest(NpcContainerEndpoint(endpoint.dimensionId,
                        NpcBlockPosition(storageAt.x,storageAt.y,storageAt.z)),0,"minecraft:dirt",64))
                    check(!transfer.uncertain && transfer.movedCount == 64);intervention=true
                }
                Incident.REMOVAL -> if (!intervention && state.supplied[0] > 0) {
                    check(h.level.destroyBlock(at,false));intervention=true
                }
                Incident.PAUSE_COMBAT -> {
                    if (!intervention && state.supplied[0] > 0) {
                        check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                        pausedRemaining=record.primary.remainingTicks;pausedFeeds=state.supplied.toList();intervention=true
                    }
                    if (record.status == TaskStatus.PAUSED) {
                        check(record.primary.remainingTicks == pausedRemaining && state.supplied.toList() == pausedFeeds)
                        if (++pauseTicks == 30) {
                            check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                            val target=arena.mob(EntityType.ZOMBIE,3.0,1.0,4.0);enemy=target
                            check(TaskService.interrupt(server,npc.npcUuid,AttackTaskDefinition(endpoint.dimensionId,target.uuid,arena.start,16.0,budget=TaskBudget(400))).status == NpcActionStatus.SUCCEEDED)
                        }
                    }
                }
                else -> Unit
            }
            if (record.status.terminal) {
                check(state.resources.entries.values.all { it.valid() })
                check(TaskCodec.write(TaskCodec.read(TaskCodec.write(record))) == TaskCodec.write(record))
                if (incident in setOf(Incident.NO_FUEL,Incident.REMOVAL)) {
                    check(record.status == TaskStatus.FAILED && record.reason == TaskReason.WORK_FAILED) { record.report() }
                    check(state.collected == 0 && TaskDelivery.inventoryCount(npc,"minecraft:iron_ingot") == 0)
                    if (incident == Incident.NO_FUEL) {
                        check(record.detail.contains("no observed progress") && machine.getItem(0).count == quantity && state.supplied.single() == quantity)
                    } else {
                        check(intervention && h.level.getBlockState(at).isAir)
                        val drops=h.level.getEntitiesOfClass(ItemEntity::class.java,arena.body.boundingBox.inflate(14.0))
                        check(TaskDelivery.inventoryCount(npc,"minecraft:raw_iron")+drops.sumOf { if (it.item.`is`(Items.RAW_IRON)) it.item.count else 0 } == quantity)
                        check(TaskDelivery.inventoryCount(npc,"minecraft:coal")+drops.sumOf { if (it.item.`is`(Items.COAL)) it.item.count else 0 } == 1)
                    }
                } else {
                    check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.MACHINE_FINISHED) { record.report() }
                    check(state.goal(definition) && TaskDelivery.inventoryCount(npc,"minecraft:iron_ingot") == quantity && machine.getItem(2).isEmpty)
                    check(TaskNavigator.distanceSquared(npc.snapshot().position,arena.start) <= 0.75*0.75)
                    if (incident == Incident.NPC_FULL) check(intervention && storage.getItem(0).count == 64 && TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 63 && TaskDelivery.inventoryCount(npc,"minecraft:coal") == 63 && TaskDelivery.inventoryCount(npc,"minecraft:dirt") == 33*64)
                    else check(TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 0 && TaskDelivery.inventoryCount(npc,"minecraft:coal") == 0)
                    if (incident == Incident.OUTPUT_BLOCKED) check(intervention && storage.getItem(0).`is`(Items.COPPER_INGOT) && storage.getItem(0).count == 64)
                    if (incident == Incident.PAUSE_COMBAT) check(pauseTicks == 30 && enemy?.isAlive == false && record.completedInterruptions == 1)
                }
                com.mojang.logging.LogUtils.getLogger().info("MACHINE_NATIVE_PROOF incident={} supplied={} collected={} transfers={} reason={}",incident,state.supplied.toList(),state.collected,state.transfers,record.reason)
                arena.succeed(npc,record)
            }
        }
    }
}
