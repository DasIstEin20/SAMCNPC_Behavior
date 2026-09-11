package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.Container
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import net.minecraftforge.registries.ForgeRegistries

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object RaisedDropCollectionGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 450, batch = "raised_drop_collection")
    fun aGroundedDropOnAHigherLedgeIsApproachedAndPhysicallyDelivered(helper: GameTestHelper) {
        for (x in 0..9) for (z in 0..7) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (x in 5..7) for (z in 2..4) helper.setBlock(BlockPos(x, 1, z), Blocks.STONE)
        helper.setBlock(BlockPos(1, 1, 5), Blocks.CHEST)
        val chestPos = helper.absolutePos(BlockPos(1, 1, 5))
        val chest = helper.level.getBlockEntity(chestPos) as Container
        val type = checkNotNull(ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.fromNamespaceAndPath("samcnpc_core", "npc")))
        val body = checkNotNull(type.create(helper.level)) as LivingEntity
        val feet = helper.absolutePos(BlockPos(2, 1, 3))
        body.moveTo(feet.x + 0.5, feet.y.toDouble(), feet.z + 0.5, -90.0F, 0.0F)
        check(helper.level.addFreshEntity(body))
        val landing = helper.absolutePos(BlockPos(6, 2, 3))
        val item = ItemEntity(helper.level, landing.x + 0.5, landing.y.toDouble(), landing.z + 0.5, ItemStack(Items.OAK_LOG, 4))
        item.setDeltaMovement(0.0, 0.0, 0.0)
        check(helper.level.addFreshEntity(item))
        var started = false; var done = false
        helper.onEachTick {
            if (done) return@onEachTick
            val server = helper.level.server
            val service = CoreNpcApi.service(server)
            val npc = service.find(body.uuid)?.let(service::runtime) ?: return@onEachTick
            if (!started) {
                if (!body.onGround() || !item.onGround()) return@onEachTick
                val box = WorkBox(position(helper.absolutePos(BlockPos(0, 1, 0))), position(helper.absolutePos(BlockPos(9, 8, 7))))
                val definition = LumberjackTaskDefinition(npc.snapshot().dimensionId, WorkArea(box), WoodSelection(listOf("samcnpc:oak")), position(chestPos), 4)
                check(TaskService.assign(server, npc, definition).status == NpcActionStatus.SUCCEEDED)
                val state = checkNotNull(TaskStore.forServer(server).get(body.uuid)?.primary?.lumberjack)
                // This fixture represents an already felled tree whose real drops settled one
                // block above the collector; it bypasses no pickup or transfer mechanics.
                state.job.phase = LumberjackDemoPhase.COLLECT_TREE_DROPS
                state.job.trunkBasePosition = position(landing)
                state.job.scanCursor = definition.area.columns
                started = true
            }
            val record = checkNotNull(TaskStore.forServer(server).get(body.uuid))
            check(record.status != TaskStatus.FAILED) { TaskService.status(server, body.uuid).orEmpty() }
            if (record.status == TaskStatus.COMPLETED) {
                check(!item.isAlive) { "the real raised drop remains" }
                val delivered = (0 until chest.containerSize).sumOf { if (chest.getItem(it).`is`(Items.OAK_LOG)) chest.getItem(it).count else 0 }
                check(delivered == 4 && checkNotNull(record.primary.lumberjack).resources.delivered(WoodSelection(listOf("samcnpc:oak"))) == 4)
                done = true; body.discard(); helper.succeed()
            }
        }
    }
    private fun position(value: BlockPos) = NpcBlockPosition(value.x, value.y, value.z)
}
