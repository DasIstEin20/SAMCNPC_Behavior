package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.inventory.InventoryFacts
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.runtime.BehaviorTargetMemory
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files

@GameTestHolder("samcnpc_local_autonomy")
@PrefixGameTestTemplate(false)
object GuardianForesterGraphGameTests {
    private const val PACK = "showcase:guardian_forester"

    private fun install() {
        val path = BehaviorRuntimeService.externalZipDirectory().resolve("guardian_forester_graph_test.zip")
        check(!Files.exists(path)) { "Use an isolated test run; graph fixture already exists" }
        Files.createDirectories(path.parent)
        checkNotNull(javaClass.getResourceAsStream("/studio/guardian_forester.zip")).use { Files.copy(it, path) }
        check(BehaviorRuntimeService.reload().accepted)
        check(PACK in BehaviorRuntimeService.activePackIds())
    }

    private fun uninstall(helper: GameTestHelper, npc: NpcFacade) {
        check(BehaviorRuntimeService.assignPacks(helper.level.server, npc.npcUuid, emptyList()).status == NpcActionStatus.SUCCEEDED)
        Files.delete(BehaviorRuntimeService.externalZipDirectory().resolve("guardian_forester_graph_test.zip"))
        check(BehaviorRuntimeService.reload().accepted)
        check(PACK !in BehaviorRuntimeService.activePackIds())
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=450, batch="guardian_equipment")
    fun actualExportEquipsAllArmorShieldAndBackupWithoutCreatingTask(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        var ready = false
        arena.onReady { npc ->
            install()
            val worn = ItemStack(Items.IRON_AXE)
            worn.damageValue = worn.maxDamage - 1
            arena.give(npc, worn)
            for (item in listOf(Items.IRON_AXE, Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.SHIELD)) arena.give(npc, ItemStack(item))
            check(BehaviorRuntimeService.assignPacks(helper.level.server, npc.npcUuid, listOf(PACK)).status == NpcActionStatus.SUCCEEDED)
            ready = true
        }
        helper.onEachTick {
            if (!ready) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = checkNotNull(service.find(arena.body.uuid)?.let(service::runtime))
            val equipment = npc.equipmentContents()
            if (equipment.mainHand.itemId != "minecraft:iron_axe" || equipment.mainHand.damage != 0 || equipment.feet.itemId != "minecraft:iron_boots") return@onEachTick
            check(equipment.head.itemId == "minecraft:iron_helmet" && equipment.chest.itemId == "minecraft:iron_chestplate" && equipment.legs.itemId == "minecraft:iron_leggings")
            check(equipment.offHand.itemId == "minecraft:shield")
            check(InventoryFacts.capture(npc).count(ItemQuery.Role(ItemQueryRole.AXE)) == 2)
            check(TaskStore.forServer(helper.level.server).get(npc.npcUuid) == null)
            ready = false
            uninstall(helper, npc)
            arena.close()
            helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=3500, batch="guardian_wood")
    fun fullGraphCooperatesWithAuthorizedChestPreparationUnloadAndWoodDelivery(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "GraphProof")
        val arena = CombatGameTestArena(helper, actor.player)
        val source = chest(helper, 3, 3)
        val excess = chest(helper, 3, -3)
        val output = chest(helper, 16, 0)
        source.setItem(0, ItemStack(Items.IRON_PICKAXE))
        source.setItem(1, ItemStack(Items.IRON_AXE))
        for (y in 1..3) helper.setBlock(BlockPos(8, y, 0), Blocks.OAK_LOG)
        arena.onReady { npc ->
            install()
            repeat(35) { arena.give(npc, ItemStack(Items.COBBLESTONE, 64)) }
            arena.assign(npc, LumberjackTaskDefinition(npc.snapshot().dimensionId,
                WorkArea(WorkBox(block(helper, 6, 1, -2), block(helper, 10, 5, 2))), WoodSelection(listOf("samcnpc:oak")),
                block(helper, 16, 1, 0), 3, budget=TaskBudget(3200), version=2))
            val policy = TaskLogisticsPolicy(anchor=arena.start, travelRadius=32.0, cooldownTicks=20,
                preparation=EnsureItems(ItemQuery.Role(ItemQueryRole.AXE), 1, ContainerChoices(listOf(block(helper,3,1,3))), 0.25, NpcEquipmentDestination.MAIN_HAND),
                unload=UnloadExcess(listOf(ItemReserve("minecraft:cobblestone",64)), ContainerChoices(listOf(block(helper,3,1,-3))), 2))
            val amended = TaskAmendments.automatic(helper.level.server, npc, actor.player, TaskChange.Logistics(policy))
            check(amended.status == NpcActionStatus.SUCCEEDED) { amended.detail }
            check(BehaviorRuntimeService.assignPacks(helper.level.server, npc.npcUuid, listOf(TaskService.LUMBERJACK_PACK_ID, PACK)).status == NpcActionStatus.SUCCEEDED)
        }
        arena.observe { npc, record ->
            if (!record.status.terminal) return@observe
            check(record.status == TaskStatus.COMPLETED) { TaskService.status(helper.level.server, npc.npcUuid).orEmpty() }
            check((0 until output.containerSize).sumOf { if(output.getItem(it).item == Items.OAK_LOG) output.getItem(it).count else 0 } == 3)
            check(source.getItem(0).item == Items.IRON_PICKAXE && source.getItem(1).isEmpty)
            check((0 until excess.containerSize).any { excess.getItem(it).item == Items.COBBLESTONE })
            check(TaskDelivery.inventoryCount(npc, "minecraft:cobblestone") >= 64)
            check(npc.equipmentContents().mainHand.itemId == "minecraft:iron_axe")
            check(record.logistics.outcomes.any { it.kind == InventoryWorkKind.ENSURE && it.supplied == mapOf("minecraft:iron_axe" to 1) })
            check(record.logistics.outcomes.all { it.reason == InventoryWorkReason.SATISFIED && it.returned })
            uninstall(helper, npc)
            actor.close()
            arena.close()
            helper.succeed()
        }
    }

    @JvmStatic @GameTest(template="lumberjackdemogametests.empty", timeoutTicks=650, batch="guardian_retreat")
    fun graphRetaliatesPhysicallyThenStopsAttackingAtLowHealth(helper: GameTestHelper) {
        val arena = CombatGameTestArena(helper)
        val enemy = arena.mob(EntityType.COW, 3.0, 0.0, 80.0)
        var ready = false
        var hurtAt = -1L
        var clearedAt = -1L
        var healthAfterClear = 0.0F
        arena.onReady { npc ->
            install()
            arena.give(npc, ItemStack(Items.IRON_AXE))
            check(BehaviorRuntimeService.assignPacks(helper.level.server, npc.npcUuid, listOf(PACK)).status == NpcActionStatus.SUCCEEDED)
            ready = true
        }
        helper.onEachTick {
            if (!ready) return@onEachTick
            val service = CoreNpcApi.service(helper.level.server)
            val npc = checkNotNull(service.find(arena.body.uuid)?.let(service::runtime))
            if (helper.tick == 600L) {
                error("retaliation stage: hurtAt=$hurtAt clearedAt=$clearedAt enemyHealth=${enemy.health} npcHealth=${arena.body.health} position=${npc.snapshot().position} equipment=${npc.equipmentContents().mainHand} target=${BehaviorTargetMemory.targetFor(npc.npcUuid)} diagnostic=${BehaviorRuntimeService.diagnostic(npc.npcUuid)}")
            }
            if (hurtAt < 0 && npc.equipmentContents().mainHand.itemId == "minecraft:iron_axe") {
                val observed = checkNotNull(npc.worldView().observeEntity(enemy.uuid))
                check(observed.alive && observed.combat?.permitted == true && observed.combat?.visible == true) { "Invalid physical attacker fixture: $observed" }
                check(arena.body.hurt(helper.level.damageSources().mobAttack(enemy), 1.0F))
                hurtAt = helper.tick
                return@onEachTick
            }
            if (enemy.health < enemy.maxHealth && clearedAt < 0) {
                arena.body.health = arena.body.maxHealth * 0.20F
                if (BehaviorTargetMemory.targetFor(npc.npcUuid) != null) return@onEachTick
                clearedAt = helper.tick
                healthAfterClear = enemy.health
            }
            if (clearedAt >= 0 && helper.tick >= clearedAt + 40) {
                check(enemy.health == healthAfterClear && enemy.isAlive)
                check(BehaviorTargetMemory.targetFor(npc.npcUuid) == null)
                check(TaskStore.forServer(helper.level.server).get(npc.npcUuid) == null)
                ready = false
                uninstall(helper, npc)
                arena.close()
                helper.succeed()
            }
        }
    }

    private fun block(helper: GameTestHelper, x: Int, y: Int, z: Int): NpcBlockPosition {
        val position = helper.absolutePos(BlockPos(x,y,z))
        return NpcBlockPosition(position.x,position.y,position.z)
    }
    private fun chest(helper: GameTestHelper, x: Int, z: Int): ChestBlockEntity {
        helper.setBlock(BlockPos(x,1,z),Blocks.CHEST)
        return helper.getBlockEntity(BlockPos(x,1,z)) as ChestBlockEntity
    }
}
