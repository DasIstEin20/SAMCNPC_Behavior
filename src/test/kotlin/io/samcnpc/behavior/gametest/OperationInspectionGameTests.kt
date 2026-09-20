package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationInspectionGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "operation_inspection")
    fun inspectionUsesCurrentActorAuthorityAndNeverMutatesTaskOrItems(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "InspectSam")
        val stranger = GameTestActor(helper.level, "InspectOther")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        arena.onReady { npc ->
            try {
                arena.give(npc, ItemStack(Items.BOW))
                arena.give(npc, ItemStack(Items.ARROW, 12))
                val denied = OperationInspectionApi.inspect(server, stranger.player, npc.npcUuid, OperationWorldRequest())
                check(denied.result.code == NpcActionCode.PERMISSION_DENIED && denied.inspection == null)
                val offThread = CompletableFuture.supplyAsync {
                    OperationInspectionApi.inspect(server, actor.player, npc.npcUuid)
                }.get(3, TimeUnit.SECONDS)
                check(offThread.result.code == NpcActionCode.NOT_READY && offThread.inspection == null)
                val idle = checkNotNull(OperationInspectionApi.inspect(server, actor.player, npc.npcUuid).inspection)
                check(idle.world == null && idle.reservations.isEmpty())
                check(idle.version == 1 && idle.operation.task == null && idle.frames.isEmpty())
                check(idle.body.inventory.size == 36 && idle.body.carriedArrowCount == 12L)
                check(idle.body.mainHand.rangedReadiness == NpcRangedResourceReadiness.RESOURCE_READY)
                val tick = idle.physical.gameTime
                val destination = arena.start.copy(x = arena.start.x + 4)
                val order = OperationOrder.Navigate(idle.physical.dimensionId, destination, speed = 0.75F)
                val assigned = OperationSupervisionApi.assign(server, actor.player, npc.npcUuid,
                    OperationAssignmentRequest(null, tick, tick + 20, order))
                check(assigned.result.status == NpcActionStatus.SUCCEEDED)
                val record = checkNotNull(TaskStore.forServer(server).get(npc.npcUuid))
                val claimPosition = NpcBlockPosition(destination.x.toInt(), destination.y.toInt(), destination.z.toInt())
                val claims = io.samcnpc.behavior.kernel.work.HarvestWorkClaims.kernel
                check(claims.renewOrClaim(npc.npcUuid, idle.physical.dimensionId, claimPosition, tick, record.id) ==
                    io.samcnpc.behavior.kernel.work.SpatialWorkClaimKernel.Result.Pending)
                val reservationBefore = claims.inspect(npc.npcUuid, tick)
                val before = TaskCodec.write(record)
                val inventoryBefore = npc.inventoryContents()
                val read = checkNotNull(OperationInspectionApi.inspect(server, actor.player, npc.npcUuid).inspection)
                check(read.operation.task?.taskId == record.id && read.frames.single().frameId == record.primary.id)
                check(read.frames.single().resources == OperationResourceInspection.NotTracked)
                check(read.reservations == listOf(OperationWorkReservation(OperationReservationKind.HARVEST_SITE,
                    record.id, idle.physical.dimensionId, claimPosition, OperationReservationTiming.Requested(tick, tick))))
                check(claims.inspect(npc.npcUuid, tick) == reservationBefore)
                val fields = read.frames.single().definition.parameters.fields
                check(fields["speed"] == OperationValue.Decimal(0.75))
                check(read.body.observedTick == read.physical.gameTime && read.physical.gameTime == read.operation.observedTick)
                check(TaskCodec.write(record) == before && npc.inventoryContents() == inventoryBefore)
                check(idle.operation.task == null && idle.frames.isEmpty())
                val position = actor.player.position()
                actor.player.teleportTo(helper.level, arena.start.x + 300, arena.start.y + 3, arena.start.z, 0F, 0F)
                val far = OperationInspectionApi.inspect(server, actor.player, npc.npcUuid, OperationWorldRequest())
                check(far.result.code == NpcActionCode.OUT_OF_RANGE && far.inspection == null)
                actor.player.teleportTo(helper.level, position.x, position.y, position.z, 0F, 0F)
                arena.close()
                val gone = OperationInspectionApi.inspect(server, actor.player, npc.npcUuid)
                check(gone.result.code == NpcActionCode.NOT_FOUND && gone.inspection == null)
                check(read.body.carriedArrowCount == 12L && read.frames.size == 1)
            } finally { arena.close(); actor.close(); stranger.close() }
            val stale = OperationInspectionApi.inspect(server, actor.player, npc.npcUuid)
            check(stale.result.code == NpcActionCode.PERMISSION_DENIED && stale.inspection == null)
            helper.succeed()
        }
    }
}
