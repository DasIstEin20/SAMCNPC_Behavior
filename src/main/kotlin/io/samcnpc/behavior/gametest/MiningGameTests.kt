package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*
import java.util.UUID

@GameTestHolder("samcnpc_mining_zoo")
@PrefixGameTestTemplate(false)
object MiningGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1500,batch="mining_exposed")
    fun exposedOreDeliversActualYieldAndPreservesOriginalStock(helper: GameTestHelper) = oreScene(helper,false,false)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1500,batch="mining_reload")
    fun minedBlockAndProducedCargoSurvivePauseAndTaskStoreReload(helper: GameTestHelper) = oreScene(helper,false,true)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=1800,batch="mining_vein")
    fun connectedVeinCannotJumpToDisconnectedOreToSatisfyQuota(helper: GameTestHelper) = oreScene(helper,true,false)

    private fun oreScene(helper: GameTestHelper,vein: Boolean,reload: Boolean) {
        val arena=CombatGameTestArena(helper); val chest=chest(helper); val server=helper.level.server
        for (x in 4..5) helper.setBlock(BlockPos(x,1,0),Blocks.IRON_ORE)
        helper.setBlock(BlockPos(9,1,0),Blocks.IRON_ORE)
        var reloaded=false; var taskId: UUID?=null
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_PICKAXE)); arena.give(npc,ItemStack(Items.RAW_IRON,5))
            val work=MiningWorkOrder(WorkArea(WorkBox(block(helper,4,1,0),block(helper,if (vein) 9 else 5,1,0))),if (vein) MiningMethod.VEIN else MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore")))
            arena.assign(npc,definition(npc,work,block(helper,0,1,3),if (vein) 3 else 2))
            taskId=TaskStore.forServer(server).get(npc.npcUuid)?.id
        }
        arena.observe { npc,record ->
            val state=checkNotNull(record.primary.mining)
            if (reload && !reloaded && state.phase == MiningPhase.COLLECT && state.selection.removed.size == 1) {
                check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val saved=TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reloaded=true
            }
            if (record.status.terminal) {
                check(record.id == taskId && (!reload || reloaded))
                check(record.status == if (vein) TaskStatus.FAILED else TaskStatus.COMPLETED) { TaskService.status(server,npc.npcUuid).orEmpty() }
                check(state.selection.removed.size == 2 && state.delivered(record.primary.definition as MiningTaskDefinition) == 2) { TaskService.status(server,npc.npcUuid).orEmpty() }
                check(count(chest,Items.RAW_IRON) == 2 && TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 5)
                check(helper.getBlockState(BlockPos(9,1,0)).`is`(Blocks.IRON_ORE)) { "mining crossed its connected/quota boundary" }
                check(helper.getBlockState(BlockPos(4,1,0)).isAir && helper.getBlockState(BlockPos(5,1,0)).isAir)
                check(TaskNavigator.distanceSquared(npc.snapshot().position,arena.start) <= 0.75*0.75)
                arena.succeed(npc,record)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2400,batch="mining_tunnel")
    fun directionalTunnelClearsOnlyExplicitGeometryAndSeparatesAccessFromResource(helper: GameTestHelper) = volume(helper,true)
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2700,batch="mining_excavation")
    fun boundedExcavationUsesTopDownRemovalAndLeavesItsFloorIntact(helper: GameTestHelper) = volume(helper,false)
    private fun volume(helper: GameTestHelper,tunnel: Boolean) {
        val arena=CombatGameTestArena(helper); val chest=chest(helper); val server=helper.level.server
        val geometry=if (tunnel) TunnelGeometry(block(helper,4,1,0),TunnelDirection.EAST,1,2,3) else null
        val area=WorkArea(geometry?.bounds() ?: WorkBox(block(helper,4,1,0),block(helper,5,2,1)))
        val work=MiningWorkOrder(area,if (tunnel) MiningMethod.TUNNEL else MiningMethod.EXCAVATION,WorkResourceIds(listOf("minecraft:iron_ore")),WorkResourceIds(listOf("minecraft:stone")),geometry)
        for (i in 0 until work.volume) { val p=work.cell(i); helper.level.setBlock(BlockPos(p.x,p.y,p.z),Blocks.STONE.defaultBlockState(),3) }
        val ore=work.cell(work.volume-1); helper.level.setBlock(BlockPos(ore.x,ore.y,ore.z),Blocks.IRON_ORE.defaultBlockState(),3)
        helper.setBlock(BlockPos(7,1,0),Blocks.DIAMOND_BLOCK)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_PICKAXE))
            val definition=definition(npc,work,block(helper,0,1,3),1).copy(outputs=WorkResourceIds(listOf("minecraft:raw_iron","minecraft:cobblestone")),counting=MiningCounting.CLEARED_VOLUME,budget=TaskBudget(2500))
            arena.assign(npc,definition)
        }
        arena.observe { npc,record ->
            if (record.status.terminal) {
                val state=checkNotNull(record.primary.mining); val d=record.primary.definition as MiningTaskDefinition
                check(record.status == TaskStatus.COMPLETED) { TaskService.status(server,npc.npcUuid).orEmpty() }
                check(state.selection.removed.size == work.volume && state.removedResources(d) == 1 && state.selection.cleared.size == work.volume)
                check(count(chest,Items.RAW_IRON) == 1 && count(chest,Items.COBBLESTONE) == work.volume-1)
                for (i in 0 until work.volume) { val p=work.cell(i); check(helper.level.getBlockState(BlockPos(p.x,p.y,p.z)).isAir) }
                check(helper.getBlockState(BlockPos(4,0,0)).`is`(Blocks.STONE) && helper.getBlockState(BlockPos(7,1,0)).`is`(Blocks.DIAMOND_BLOCK))
                arena.succeed(npc,record)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=500,batch="mining_fluid")
    fun adjacentFluidStopsBeforeBreakingAndDoesNotCreditExistingCargo(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val chest=chest(helper)
        helper.setBlock(BlockPos(4,1,0),Blocks.IRON_ORE); helper.setBlock(BlockPos(4,1,1),Blocks.WATER)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_PICKAXE)); arena.give(npc,ItemStack(Items.RAW_IRON,3))
            val work=MiningWorkOrder(WorkArea(WorkBox(block(helper,4,1,0),block(helper,4,1,0))),MiningMethod.EXCAVATION,WorkResourceIds(listOf("minecraft:iron_ore")))
            arena.assign(npc,definition(npc,work,block(helper,0,1,3),1))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            val state=checkNotNull(record.primary.mining)
            check(record.status == TaskStatus.FAILED && state.stop == MiningProblem.FLUID) { record.detail }
            check(state.selection.removed.isEmpty() && count(chest,Items.RAW_IRON) == 0 && TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 3)
            check(helper.getBlockState(BlockPos(4,1,0)).`is`(Blocks.IRON_ORE)); arena.succeed(npc,record)
        } }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=700,batch="mining_changed")
    fun anotherActorRemovingThePendingBlockCannotBecomeNpcMiningCredit(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper); val chest=chest(helper); var changed=false
        helper.setBlock(BlockPos(4,1,0),Blocks.IRON_ORE)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_PICKAXE))
            val work=MiningWorkOrder(WorkArea(WorkBox(block(helper,4,1,0),block(helper,4,1,0))),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:iron_ore")))
            arena.assign(npc,definition(npc,work,block(helper,0,1,3),1).copy(counting=MiningCounting.REMOVED_RESOURCE_BLOCKS))
        }
        arena.observe { npc,record ->
            if (!changed && npc.snapshot().blockBreak != null) { helper.setBlock(BlockPos(4,1,0),Blocks.AIR); changed=true }
            if (record.status.terminal) {
                check(changed && record.status == TaskStatus.FAILED && checkNotNull(record.primary.mining).selection.removed.isEmpty()) { record.detail }
                check(count(chest,Items.RAW_IRON) == 0); arena.succeed(npc,record)
            }
        }
    }
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty",timeoutTicks=2300,batch="mining_command_amendment")
    fun commandQuantityChangeAndNewResourceArchivePhysicalWorkWithoutResettingTaskIdentity(helper: GameTestHelper) {
        val actor=GameTestActor(helper.level,"MiningAmend")
        val arena=CombatGameTestArena(helper,actor.player); val output=chest(helper); val server=helper.level.server
        helper.setBlock(BlockPos(0,1,-3),Blocks.CHEST)
        val nextOutput=helper.level.getBlockEntity(helper.absolutePos(BlockPos(0,1,-3))) as ChestBlockEntity
        for (x in 4..5) helper.setBlock(BlockPos(x,1,0),Blocks.IRON_ORE)
        for (x in 8..9) helper.setBlock(BlockPos(x,1,0),Blocks.GOLD_ORE)
        var increased=false; var changed=false; var original: UUID?=null; var reportedFailures=0
        fun coordinates(p: NpcBlockPosition)="${p.x} ${p.y} ${p.z}"
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.IRON_PICKAXE)); arena.give(npc,ItemStack(Items.RAW_IRON,5))
            val command="samcnpc behavior task assign AmendProof mine exposed ${coordinates(block(helper,4,1,0))} ${coordinates(block(helper,5,1,0))} ${coordinates(block(helper,0,1,3))} \"minecraft:iron_ore\" - \"minecraft:raw_iron\" delivered_items 1 2200 true"
            val messages=mutableListOf<String>()
            val source=actor.player.createCommandSourceStack().withSource(object : net.minecraft.commands.CommandSource {
                override fun sendSystemMessage(message: net.minecraft.network.chat.Component) { messages.add(message.string) }
                override fun acceptsSuccess()=true
                override fun acceptsFailure()=true
                override fun shouldInformAdmins()=false
            })
            check(server.commands.performPrefixedCommand(source,command) == 1) { "real mining command failed: $messages" }
            original=TaskStore.forServer(server).get(npc.npcUuid)?.id
        }
        arena.observe { npc,record ->
            val state=checkNotNull(record.primary.mining)
            if (record.totalFailures != reportedFailures) {
                reportedFailures=record.totalFailures
                com.mojang.logging.LogUtils.getLogger().warn("MINING_AMENDMENT_RETRY report={} phase={} target={} observed={} navigation={}",
                    record.report(),state.phase,state.target,npc.snapshot().position,npc.snapshot().navigation)
            }
            if (!increased && state.phase == MiningPhase.COLLECT && state.selection.removed.size == 1) {
                check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Quantity(2,QuantityChangeMode.TOTAL)).status == NpcActionStatus.SUCCEEDED)
                check(record.amendments.pending != null && record.amendments.revision == 0) { "quantity amendment bypassed physical boundary" }
                increased=true
            }
            if (!changed && increased && state.delivered(record.primary.definition as MiningTaskDefinition) == 2) {
                val old=record.primary.definition as MiningTaskDefinition
                val next=old.copy(work=MiningWorkOrder(WorkArea(WorkBox(block(helper,8,1,0),block(helper,9,1,0))),MiningMethod.EXPOSED,WorkResourceIds(listOf("minecraft:gold_ore"))),
                    outputs=WorkResourceIds(listOf("minecraft:raw_gold")),destinations=ContainerChoices(listOf(block(helper,0,1,-3))))
                check(TaskAmendments.automatic(server,npc,actor.player,TaskChange.Replace(next,ObjectiveChangeMode.NEW_OBJECTIVE)).status == NpcActionStatus.SUCCEEDED)
                check(record.amendments.objectives.single().confirmed == 2 && record.amendments.revision == 2)
                changed=true
            }
            if (record.status.terminal) {
                check(record.status == TaskStatus.COMPLETED && increased && changed && record.id == original) { TaskService.status(server,npc.npcUuid).orEmpty() }
                val report=record.amendments.objectives.single()
                check(report.confirmed == 2 && report.resources["minecraft:raw_iron"]?.delivered == 2)
                check(report == TaskObjectiveCodec.read(TaskObjectiveCodec.write(report)))
                check(count(output,Items.RAW_IRON) == 2 && count(nextOutput,Items.RAW_GOLD) == 2 && TaskDelivery.inventoryCount(npc,"minecraft:raw_iron") == 5)
                check(checkNotNull(record.primary.mining).resources.delivered("minecraft:raw_iron") == 0)
                check(record.primary.remainingTicks < 2200 && record.totalFailures == 0) { "Unexpected retry: ${record.report()}; total=${record.totalFailures}" }
                actor.close(); arena.succeed(npc,record)
            }
        }
    }
    private fun definition(npc: NpcFacade,work: MiningWorkOrder,recipient: NpcBlockPosition,quantity: Int) = MiningTaskDefinition(npc.snapshot().dimensionId,work,
        WorkResourceIds(listOf("minecraft:raw_iron")),ContainerChoices(listOf(recipient)),quantity,MiningCounting.DELIVERED_ITEMS,npc.snapshot().position,
        returnTo=npc.snapshot().position,budget=TaskBudget(1700))
    private fun chest(h: GameTestHelper): ChestBlockEntity { h.setBlock(BlockPos(0,1,3),Blocks.CHEST); return h.level.getBlockEntity(h.absolutePos(BlockPos(0,1,3))) as ChestBlockEntity }
    private fun count(c: ChestBlockEntity,item: Item)=(0 until c.containerSize).sumOf { if (c.getItem(it).`is`(item)) c.getItem(it).count else 0 }
    private fun block(h: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=h.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
}
