package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.world.item.*
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraftforge.gametest.*
import java.util.UUID

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object PublicCargoAmendmentGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1200, batch = "public_cargo_amendment")
    fun sourceAndTimeEditsUseActualCargoAndDoNotReplayAnExtension(helper: GameTestHelper) {
        val server = helper.level.server
        val actor = GameTestActor(helper.level, "PublicCargo")
        val arena = CombatGameTestArena(helper, actor.player)
        fun chest(x: Int, z: Int): ChestBlockEntity {
            val at = BlockPos(x, 1, z); helper.setBlock(at, Blocks.CHEST)
            return helper.level.getBlockEntity(helper.absolutePos(at)) as ChestBlockEntity
        }
        fun pos(c: ChestBlockEntity) = NpcBlockPosition(c.blockPos.x, c.blockPos.y, c.blockPos.z)
        fun count(c: ChestBlockEntity) = (0 until c.containerSize).sumOf { index ->
            val stack = c.getItem(index)
            if (stack.item == Items.OAK_LOG) stack.count else 0
        }
        val oldSource = chest(2, 3); val newSource = chest(5, 3); val destination = chest(15, -3)
        oldSource.setItem(0, ItemStack(Items.OAK_LOG, 64)); newSource.setItem(0, ItemStack(Items.OAK_LOG, 6))
        var taskId: UUID? = null
        arena.onReady { npc ->
            arena.give(npc, ItemStack(Items.OAK_LOG, 5))
            arena.assign(npc, TransportTaskDefinition(npc.snapshot().dimensionId,
                ContainerChoices(listOf(pos(oldSource))), ContainerChoices(listOf(pos(destination))),
                "minecraft:oak_log", 6, arena.start, keepAtLeast = 5, returnTo = arena.start, budget = TaskBudget(ticks = 900)))
            check(TaskService.pause(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
            val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid)); taskId = record.id
            val now = npc.snapshot().gameTime
            val sources = OperationAmendmentRequest(record.id, UUID.randomUUID(), 0, now, now + 100,
                OperationChange.Sources(OperationContainers(listOf(pos(newSource)))))
            val changed = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, sources)
            check(changed.result.status == NpcActionStatus.SUCCEEDED && changed.amendment?.outcome == OperationAmendmentOutcome.APPLIED)
            check(record.primary.remainingTicks == 900 && record.amendments.revision == 1)
            val extend = sources.copy(requestId = UUID.randomUUID(), expectedDefinitionRevision = 1, change = OperationChange.ExtendTime(77))
            val beforeLookup = TaskCodec.write(record)
            val absent = OperationSupervisionApi.amendmentReceipt(server, actor.player, npc.npcUuid, extend)
            check(absent.result.code == NpcActionCode.NOT_FOUND && absent.amendment == null)
            check(TaskCodec.write(record) == beforeLookup) { "receipt lookup submitted an unrecorded time extension" }
            val added = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, extend)
            check(added.result.status == NpcActionStatus.SUCCEEDED && added.amendment?.outcome == OperationAmendmentOutcome.APPLIED)
            check(record.primary.remainingTicks == 977 && record.primary.definition.budget.ticks == 977)
            val before = TaskCodec.write(record)
            val lookedUp = OperationSupervisionApi.amendmentReceipt(server, actor.player, npc.npcUuid, extend)
            check(lookedUp.amendment == added.amendment && TaskCodec.write(record) == before)
            val wrongPayload = OperationSupervisionApi.amendmentReceipt(server, actor.player, npc.npcUuid,
                extend.copy(change = OperationChange.ExtendTime(78)))
            check(wrongPayload.result.code == NpcActionCode.NOT_FOUND && wrongPayload.amendment == null)
            check(TaskCodec.write(record) == before)

            val replay = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, extend)
            check(replay.amendment == added.amendment && TaskCodec.write(record) == before)
            val conflict = OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, extend.copy(change = OperationChange.ExtendTime(78)))
            check(conflict.result.code == NpcActionCode.CONFLICT && conflict.amendment == null && TaskCodec.write(record) == before)
            val impossible = sources.copy(requestId = UUID.randomUUID(), expectedDefinitionRevision = 2, change = OperationChange.Sources(null))
            check(OperationSupervisionApi.amend(server, actor.player, npc.npcUuid, impossible).result.status == NpcActionStatus.REJECTED)
            check(TaskCodec.write(record) == before && record.amendments.revision == 2)
            check(TaskService.resume(server, npc.npcUuid).status == NpcActionStatus.SUCCEEDED)
        }
        arena.observe { npc, record ->
            if (record.status.terminal) {
                check(record.id == taskId && record.status == TaskStatus.COMPLETED && record.amendments.revision == 2)
                check(count(oldSource) == 64 && count(newSource) == 0 && count(destination) == 6)
                check(TaskDelivery.inventoryCount(npc, "minecraft:oak_log") == 5)
                check(record.primary.remainingTicks < 977 && record.totalFailures == 0)
                check(TaskNavigator.distanceSquared(npc.snapshot().position, arena.start) <= 0.75 * 0.75)
                arena.succeed(npc, record); actor.close()
            }
        }
    }
}
