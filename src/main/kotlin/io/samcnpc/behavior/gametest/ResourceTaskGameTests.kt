package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object ResourceTaskGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "resource_resume")
    fun partialDeliverySurvivesCheckpointReloadThenResumesWithoutRepeatingItsReceipt(helper: GameTestHelper) = reloadAfterPartial(helper, 0)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "resource_npc_mismatch")
    fun oldNpcSaveWithNewContainerAndTaskFailsBeforeAnyReplay(helper: GameTestHelper) = reloadAfterPartial(helper, 1)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "resource_container_mismatch")
    fun oldContainerSaveWithNewNpcAndTaskFailsBeforeAnyReplay(helper: GameTestHelper) = reloadAfterPartial(helper, 2)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "resource_cancel")
    fun cancellingAPartialDeliveryRetainsActualStockAndConfirmedReport(helper: GameTestHelper) = reloadAfterPartial(helper, 3)

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 350, batch = "resource_masked_reload")
    fun actualOldBodyReloadCannotBeMaskedByDroppingItsExtraStockBeforeResume(helper: GameTestHelper) {
        val fixture = fixture(helper, 3, true)
        var body = fixture.first
        val chest = fixture.second
        var oldBody: CompoundTag? = null
        var stage = 0
        var done = false
        helper.onEachTick {
            if (done || stage == 2) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (stage == 0) {
                oldBody = body.saveWithoutId(CompoundTag())
                check(TaskService.assign(server, npc, definition(helper, 3, 12, 4)).status == NpcActionStatus.SUCCEEDED) { "fixture assignment failed" }
                stage = 1
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            if (stage == 1 && record.primary.resources?.delivered == 6) {
                check(TaskService.pause(server, body.uuid).status == NpcActionStatus.SUCCEEDED) { "fixture pause failed" }
                val savedTask = TaskStore.forServer(server).save(CompoundTag())
                body.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK)
                stage = 2
                helper.runAfterDelay(2) {
                    val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
                    body = checkNotNull(type.create(helper.level)) as LivingEntity
                    body.load(checkNotNull(oldBody))
                    check(helper.level.addFreshEntity(body)) { "restored body could not register" }
                    val service = CoreNpcApi.service(server)
                    val restored = checkNotNull(service.runtime(checkNotNull(service.find(body.uuid))))
                    check(checkNotNull(restored.inventoryLoadSnapshot()).inventory[0].count == 16) { "loaded snapshot was not the old 16-item stock: ${restored.inventoryLoadSnapshot()?.inventory}" }
                    val dropped = restored.dropInventoryStack(0, 6)
                    check(dropped.status == NpcActionStatus.SUCCEEDED) { "actual drop failed: $dropped" }
                    check(body.mainHandItem.count == 10 && count(chest) == 64) { "masked live checkpoint is wrong: npc=${body.mainHandItem.count} chest=${count(chest)}" }
                    server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(savedTask))
                    chest.setItem(1, ItemStack.EMPTY)
                    chest.setChanged()
                    val resumed = TaskService.resume(server, body.uuid)
                    check(resumed.status == NpcActionStatus.SUCCEEDED) { "fixture resume failed: $resumed" }
                    stage = 3
                }
            } else if (stage == 3 && record.status.terminal) {
                check(record.status == TaskStatus.FAILED && record.reason == TaskReason.STATE_MISMATCH) { TaskService.status(server, body.uuid).orEmpty() }
                check(record.primary.resources?.delivered == 6 && count(chest) == 64) { "mismatch replayed delivery: ${record.primary.resources?.delivered} chest=${count(chest)}" }
                check(body.mainHandItem.count == 10) { "retained inventory changed: ${body.mainHandItem.count}" }
                check(record.detail.contains("loaded NPC stock 16")) { "wrong failure cause: ${record.detail}" }
                check(npc.snapshot().navigation == null && npc.snapshot().blockBreak == null) { "mismatch left active controls" }
                done = true; body.discard(); helper.succeed()
            }
        }
    }

    private fun reloadAfterPartial(helper: GameTestHelper, scenario: Int) {
        val fixture = fixture(helper, 3, true)
        val body = fixture.first
        val chest = fixture.second
        var phase = 0
        var age = 0
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (phase == 0) {
                check(TaskService.assign(server, npc, definition(helper, 3, 12, 4)).status == NpcActionStatus.SUCCEEDED)
                check(TaskService.executeSelected(server, npc, npc.worldView()).status == NpcActionStatus.REJECTED) { "navigation-only action acquired delivery inventory effects" }
                check(body.mainHandItem.count == 16 && chest.getItem(0).count == 58)
                phase = 1
            }
            var record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            if (phase == 1 && record.primary.resources?.delivered == 6) {
                check(body.mainHandItem.count == 10 && chest.getItem(0).count == 64)
                val receipt = checkNotNull(record.primary.resources?.receipt)
                check(TaskService.pause(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                val saved = TaskStore.forServer(server).save(CompoundTag())
                check(BehaviorRuntimeService.reload().accepted)
                server.overworld().dataStorage.set("samcnpc_behavior_tasks", TaskStore.load(saved))
                record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
                check(record.status == TaskStatus.PAUSED && record.primary.resources?.receipt == receipt)
                if (scenario == 1) body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_LOG, 16))
                if (scenario == 2) chest.setItem(0, ItemStack(Items.OAK_LOG, 58))
                if (scenario == 3) {
                    check(TaskService.cancel(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                } else {
                    chest.setItem(1, ItemStack.EMPTY)
                    chest.setChanged()
                    check(TaskService.resume(server, body.uuid).status == NpcActionStatus.SUCCEEDED)
                }
                phase = 2
                age = 0
            }
            if (phase == 2 && ++age >= 25) {
                val progress = checkNotNull(record.primary.resources)
                when (scenario) {
                    0 -> {
                        check(record.status == TaskStatus.COMPLETED && record.reason == TaskReason.DELIVERED) { TaskService.status(server, body.uuid).orEmpty() }
                        check(progress.delivered == 12 && body.mainHandItem.count == 4 && count(chest) == 70)
                        check(progress.receipt?.sequence == 2)
                    }
                    1, 2 -> {
                        check(record.status == TaskStatus.FAILED && record.reason == TaskReason.STATE_MISMATCH) { TaskService.status(server, body.uuid).orEmpty() }
                        check(progress.delivered == 6 && progress.uncertain)
                        check(body.mainHandItem.count == if (scenario == 1) 16 else 10)
                        check(count(chest) == if (scenario == 2) 58 else 64)
                    }
                    3 -> {
                        check(record.status == TaskStatus.CANCELLED && progress.delivered == 6)
                        check(body.mainHandItem.count == 10 && count(chest) == 64)
                    }
                }
                check(npc.snapshot().navigation == null && npc.snapshot().control == null)
                check(BehaviorRuntimeService.assignedPacks(server, body.uuid).isEmpty())
                finished = true
                body.discard()
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 250, batch = "resource_full")
    fun fullStorageExhaustsABoundedRetryBudgetAndReportsActualShortage(helper: GameTestHelper) {
        val (body, chest) = fixture(helper, 3, true)
        var started = false
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (!started) {
                check(TaskService.assign(server, npc, definition(helper, 3, 12, 4)).status == NpcActionStatus.SUCCEEDED)
                started = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            if (record.status == TaskStatus.FAILED) {
                check(record.reason == TaskReason.RETRY_LIMIT && record.totalFailures == 3)
                check(record.primary.resources?.delivered == 6 && body.mainHandItem.count == 10 && count(chest) == 64)
                check(TaskService.status(server, body.uuid)?.contains("shortage=6") == true)
                finished = true; body.discard(); helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 300, batch = "resource_reserve")
    fun deliveryWalksToStorageAndNeverConsumesTheRetainedReserve(helper: GameTestHelper) {
        val (body, chest) = fixture(helper, 9, false)
        var started = false
        var sawNavigation = false
        var finished = false
        helper.onEachTick {
            if (finished) return@onEachTick
            val npc = runtime(helper, body) ?: return@onEachTick
            val server = helper.level.server
            if (!started) {
                check(TaskService.assign(server, npc, definition(helper, 9, 20, 4)).status == NpcActionStatus.SUCCEEDED)
                started = true
            }
            sawNavigation = sawNavigation || npc.snapshot().navigation != null
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            if (record.status.terminal) {
                check(record.status == TaskStatus.FAILED && record.reason == TaskReason.MISSING_RESOURCE) { TaskService.status(server, body.uuid).orEmpty() }
                check(sawNavigation && record.primary.resources?.delivered == 12)
                check(body.mainHandItem.count == 4 && count(chest) == 12)
                check(TaskService.status(server, body.uuid)?.contains("shortage=8") == true)
                finished = true; body.discard(); helper.succeed()
            }
        }
    }

    private fun definition(helper: GameTestHelper, x: Int, quantity: Int, reserve: Int): DeliveryTaskDefinition {
        val position = helper.absolutePos(BlockPos(x, 1, 3))
        return DeliveryTaskDefinition(helper.level.dimension().location().toString(),
            NpcBlockPosition(position.x, position.y, position.z), "minecraft:oak_log", quantity, reserve, TaskBudget(ticks = 300))
    }
    private fun count(container: Container): Int = (0 until container.containerSize).sumOf { slot ->
        val stack = container.getItem(slot)
        if (stack.`is`(Items.OAK_LOG)) stack.count else 0
    }
    private fun runtime(helper: GameTestHelper, body: LivingEntity): NpcFacade? {
        if (!body.onGround()) return null
        val service = CoreNpcApi.service(helper.level.server)
        return service.find(body.uuid)?.let(service::runtime)
    }
    private fun fixture(helper: GameTestHelper, chestX: Int, full: Boolean): Pair<LivingEntity, Container> {
        for (x in 0..11) for (z in 0..7) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        helper.setBlock(BlockPos(chestX, 1, 3), Blocks.CHEST)
        val chest = helper.level.getBlockEntity(helper.absolutePos(BlockPos(chestX, 1, 3))) as Container
        if (full) {
            for (slot in 0 until chest.containerSize) chest.setItem(slot, ItemStack(Items.STONE, 64))
            chest.setItem(0, ItemStack(Items.OAK_LOG, 58))
        }
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(1, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, -90.0F, 0.0F)
        body.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.OAK_LOG, 16))
        check(helper.level.addFreshEntity(body))
        return body to chest
    }
}
