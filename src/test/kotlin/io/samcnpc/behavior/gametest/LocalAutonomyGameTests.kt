package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.inventory.*
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
import java.nio.file.Files

@GameTestHolder("samcnpc_local_autonomy")
@PrefixGameTestTemplate(false)
object LocalAutonomyGameTests {
    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=3500, batch="local_tool_chest")
    fun permittedToolPreparationSurvivesStoreReloadThenChopsAndDelivers(helper: GameTestHelper) = wood(helper, false, false)

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=3500, batch="local_backup")
    fun lowDurabilityToolUsesCarriedBackupThenContinuesPhysicalWork(helper: GameTestHelper) = wood(helper, true, false)

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=3500, batch="local_space")
    fun fullInventoryUnloadsOnlyAllowedExcessAndResumesWoodWork(helper: GameTestHelper) = wood(helper, false, true)

    private fun wood(helper: GameTestHelper, backup: Boolean, full: Boolean) {
        val actor = GameTestActor(helper.level, "LocalPrep")
        val arena = CombatGameTestArena(helper, actor.player); val server = helper.level.server
        val source = chest(helper, 3, 3); val extras = chest(helper, 3, -3); val output = chest(helper, 16, 0)
        source.setItem(0, ItemStack(Items.IRON_PICKAXE)); source.setItem(1, ItemStack(Items.IRON_AXE))
        source.setItem(2, ItemStack(Items.STONE_SHOVEL))
        for (y in 1..3) helper.setBlock(BlockPos(8, y, 0), Blocks.OAK_LOG)
        var reloaded = false
        arena.onReady { npc ->
            if (backup) {
                val worn = ItemStack(Items.IRON_AXE); worn.damageValue = worn.maxDamage - 1
                arena.give(npc, worn); arena.give(npc, ItemStack(Items.IRON_AXE))
            }
            if (full) { arena.give(npc, ItemStack(Items.IRON_AXE)); repeat(35) { arena.give(npc, ItemStack(Items.COBBLESTONE, 64)) } }
            arena.assign(npc, LumberjackTaskDefinition(npc.snapshot().dimensionId,
                WorkArea(WorkBox(block(helper,6,1,-2), block(helper,10,5,2))), WoodSelection(listOf("samcnpc:oak")),
                block(helper,16,1,0), 3, budget=TaskBudget(3200), version=2))
            val policy = TaskLogisticsPolicy(anchor=arena.start, travelRadius=32.0, cooldownTicks=20,
                preparation=EnsureItems(ItemQuery.Role(ItemQueryRole.AXE),1,ContainerChoices(listOf(block(helper,3,1,3))),0.2,NpcEquipmentDestination.MAIN_HAND),
                unload=if (full) UnloadExcess(listOf(ItemReserve("minecraft:cobblestone",64),ItemReserve("minecraft:oak_log",0),ItemReserve("minecraft:iron_axe",0)),ContainerChoices(listOf(block(helper,3,1,-3))),2) else null)
            val configured = TaskAmendments.automatic(server,npc,actor.player,TaskChange.Logistics(policy.copy(anchor=null,preparation=null,unload=null)))
            check(configured.status == NpcActionStatus.SUCCEEDED) { configured.detail }
            val supply=block(helper,3,1,3);val unload=block(helper,3,1,-3)
            check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),
                "samcnpc behavior task logistics ${npc.npcUuid} prepare \"@axe\" 1 \"${supply.x},${supply.y},${supply.z}\" MAIN_HAND 0.2 0")==1)
            if(full) check(server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),
                "samcnpc behavior task logistics ${npc.npcUuid} free_slots \"${unload.x},${unload.y},${unload.z}\" \"minecraft:cobblestone=64;minecraft:oak_log=0;minecraft:iron_axe=0\" 2")==1)
        }
        arena.observe { npc, record ->
            if (!backup && !full && !reloaded && record.active.inventory?.resources?.entries?.get("minecraft:iron_axe")?.supplied == 1) {
                check(TaskService.pause(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                val saved = TaskStore.forServer(server).save(CompoundTag())
                val before = TaskCodec.write(record)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks",TaskStore.load(saved))
                val restored = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                check(TaskCodec.write(restored) == before)
                check(TaskService.resume(server,npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
                reloaded = true; return@observe
            }
            if (!record.status.terminal) return@observe
            check(record.status == TaskStatus.COMPLETED) { TaskService.status(server,npc.npcUuid).orEmpty() }
            check(count(output,Items.OAK_LOG) == 3 && count(extras,Items.OAK_LOG) == 0)
            check(npc.equipmentContents().mainHand.itemId == "minecraft:iron_axe" && ItemQuery.durability(npc.equipmentContents().mainHand) > 0.2)
            check(count(source,Items.IRON_PICKAXE) == 1 && count(source,Items.STONE_SHOVEL) == 1)
            check(record.logistics.outcomes.all { it.reason == InventoryWorkReason.SATISFIED && it.returned }) { record.logistics.outcomes.toString() }
            when {
                full -> {
                    check(record.logistics.outcomes.map { it.kind } == listOf(InventoryWorkKind.UNLOAD, InventoryWorkKind.UNLOAD))
                    check(record.logistics.outcomes.map { it.unloaded } == listOf(mapOf("minecraft:cobblestone" to 128), mapOf("minecraft:cobblestone" to 64)))
                    check(count(extras,Items.COBBLESTONE) == 192 && count(extras,Items.IRON_AXE) == 0)
                    check(TaskDelivery.inventoryCount(npc,"minecraft:cobblestone") == 2048)
                }
                backup -> {
                    check(record.logistics.outcomes.single().supplied.isEmpty() && count(source,Items.IRON_AXE) == 1)
                    check(npc.inventoryContents().any { it.stack.itemId == "minecraft:iron_axe" && it.stack.damage == it.stack.maxDamage - 1 })
                }
                else -> check(reloaded && record.logistics.outcomes.single().supplied == mapOf("minecraft:iron_axe" to 1) && count(source,Items.IRON_AXE) == 0)
            }
            actor.close(); arena.succeed(npc,record)
        }
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=500, batch="local_exact")
    fun charcoalCannotSatisfyAnExactCoalRequirement(helper: GameTestHelper) {
        val actor=GameTestActor(helper.level,"ExactCoal");val arena = CombatGameTestArena(helper,actor.player)
        arena.onReady { npc ->
            arena.give(npc,ItemStack(Items.CHARCOAL,8))
            check(helper.level.server.commands.performPrefixedCommand(actor.player.createCommandSourceStack(),
                "samcnpc behavior task assign ${npc.npcUuid} ensure \"minecraft:coal\" 2 \"-\" NONE 0 0")==1)
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            check(record.status == TaskStatus.FAILED && record.logistics.outcomes.single().reason == InventoryWorkReason.MISSING_RESOURCE)
            check(TaskDelivery.inventoryCount(npc,"minecraft:charcoal") == 8 && TaskDelivery.inventoryCount(npc,"minecraft:coal") == 0)
            actor.close();arena.succeed(npc,record)
        } }
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=300, batch="local_studio_zip")
    fun actualStudioExportLoadsAsExternalZipAndEquipsThroughRegisteredRules(helper: GameTestHelper) {
        val arena=CombatGameTestArena(helper);val server=helper.level.server
        val path=BehaviorRuntimeService.externalZipDirectory().resolve("studio_local_autonomy_probe.zip")
        val original=BehaviorRuntimeService.activePackIds();var started=false
        arena.onReady { npc ->
            check(!Files.exists(path)) { "Studio fixture already exists; use an isolated run directory" }
            Files.createDirectories(path.parent)
            checkNotNull(javaClass.getResourceAsStream("/studio/example_tool_preparation.zip")).use { Files.copy(it,path) }
            check(BehaviorRuntimeService.reload().accepted)
            check(BehaviorRuntimeService.activePackIds()==(original+"example:tool_preparation").sorted())
            val worn=ItemStack(Items.IRON_AXE);worn.damageValue=worn.maxDamage-1
            arena.give(npc,worn);arena.give(npc,ItemStack(Items.IRON_AXE))
            check(BehaviorRuntimeService.assignPacks(server,npc.npcUuid,listOf("example:tool_preparation")).status==NpcActionStatus.SUCCEEDED)
            started=true
        }
        helper.onEachTick {
            if(!started) return@onEachTick
            val service=CoreNpcApi.service(server);val npc=checkNotNull(service.find(arena.body.uuid)?.let(service::runtime))
            if(npc.equipmentContents().mainHand.damage!=0) return@onEachTick
            check(npc.equipmentContents().mainHand.itemId=="minecraft:iron_axe")
            check(npc.inventoryContents().count { it.stack.itemId=="minecraft:iron_axe" }==2)
            check(TaskStore.forServer(server).get(npc.npcUuid)==null)
            check(BehaviorRuntimeService.assignPacks(server,npc.npcUuid,emptyList()).status==NpcActionStatus.SUCCEEDED)
            Files.delete(path);check(BehaviorRuntimeService.reload().accepted);check(BehaviorRuntimeService.activePackIds()==original)
            started=false;arena.close();helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=500, batch="local_unknown")
    fun lockedChestIsUnknownAndDoesNotBecomeAnEmptySupply(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper); val source = chest(helper,2,2)
        source.setItem(0,ItemStack(Items.IRON_AXE))
        source.load(source.saveWithoutMetadata().apply { putString("Lock","private") })
        arena.onReady { npc ->
            val endpoint=block(helper,2,1,2)
            check(VisibleContainerFacts.observe(npc.worldView(),NpcStockQuery(endpoint,"minecraft:air")) == null)
            arena.assign(npc,InventoryTaskDefinition(npc.snapshot().dimensionId,EnsureItems(ItemQuery.Role(ItemQueryRole.AXE),1,ContainerChoices(listOf(endpoint)),destination=NpcEquipmentDestination.MAIN_HAND),arena.start,version=3))
        }
        arena.observe { npc,record -> if (record.status.terminal) {
            check(record.status == TaskStatus.FAILED && record.logistics.outcomes.single().reason == InventoryWorkReason.SOURCE_UNAVAILABLE)
            check(count(source,Items.IRON_AXE) == 1 && record.logistics.outcomes.single().supplied.isEmpty())
            arena.succeed(npc,record)
        } }
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=1500, batch="local_shared")
    fun twoNpcsWithdrawDistinctPhysicalToolsAndKeepOneSharedReserve(helper: GameTestHelper) {
        val actor=GameTestActor(helper.level,"LocalShared"); val arena=CombatGameTestArena(helper,actor.player)
        val source=chest(helper,3,2); repeat(3) { source.setItem(it,ItemStack(Items.IRON_AXE)) }
        var other: NpcFacade? = null
        arena.onReady { npc ->
            val service=CoreNpcApi.service(helper.level.server)
            val summoned=service.summon(NpcSummonRequest(actor.player.uuid,"LocalSecond",npc.snapshot().dimensionId,NpcPosition(arena.start.x,arena.start.y,arena.start.z+1),-90F))
            other=checkNotNull(service.runtime(checkNotNull(summoned.handle)))
            for (body in listOf(npc,checkNotNull(other))) {
                arena.assign(body,InventoryTaskDefinition(body.snapshot().dimensionId,EnsureItems(ItemQuery.Role(ItemQueryRole.AXE),1,
                    ContainerChoices(listOf(block(helper,3,1,2))),destination=NpcEquipmentDestination.MAIN_HAND,sourceReserve=1),body.snapshot().position,version=3))
            }
        }
        arena.observe { npc,record ->
            val second=checkNotNull(other); val secondRecord=checkNotNull(TaskStore.forServer(helper.level.server).get(second.npcUuid))
            if (!record.status.terminal || !secondRecord.status.terminal) return@observe
            for (pair in listOf(npc to record,second to secondRecord)) {
                check(pair.second.status == TaskStatus.COMPLETED) { pair.second.report() }
                check(pair.second.logistics.outcomes.single().supplied == mapOf("minecraft:iron_axe" to 1))
                check(pair.first.equipmentContents().mainHand.itemId == "minecraft:iron_axe")
                check(TaskCodec.read(TaskCodec.write(pair.second)).report() == pair.second.report())
            }
            check(count(source,Items.IRON_AXE) == 1)
            helper.level.getEntity(second.npcUuid)?.discard(); actor.close(); arena.succeed(npc,record)
        }
    }

    private fun chest(helper: GameTestHelper,x: Int,z: Int): ChestBlockEntity {
        helper.setBlock(BlockPos(x,1,z),Blocks.CHEST)
        return helper.level.getBlockEntity(helper.absolutePos(BlockPos(x,1,z))) as ChestBlockEntity
    }
    private fun count(chest: ChestBlockEntity,item: Item)=(0 until chest.containerSize).sumOf { if(chest.getItem(it).`is`(item)) chest.getItem(it).count else 0 }
    private fun block(helper: GameTestHelper,x: Int,y: Int,z: Int): NpcBlockPosition { val p=helper.absolutePos(BlockPos(x,y,z)); return NpcBlockPosition(p.x,p.y,p.z) }
}
