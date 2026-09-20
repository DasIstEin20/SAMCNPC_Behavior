package io.samcnpc.behavior.gametest

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object OperationWorldInspectionGameTests {
    @JvmStatic
    @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 160, batch = "operation_world_inspection")
    fun authorizedWorldCaptureRejectsOcclusionInvisiblePlayersSpectatorsAndBlindness(helper: GameTestHelper) {
        val actor = GameTestActor(helper.level, "VisualSam")
        val other = GameTestActor(helper.level, "VisualOther")
        val arena = CombatGameTestArena(helper, actor.player)
        val server = helper.level.server
        arena.onReady { npc ->
            try {
                helper.setBlock(BlockPos(4, 1, 0), Blocks.DIAMOND_ORE)
                for (y in 1..5) for (z in -2..3) helper.setBlock(BlockPos(5, y, z), Blocks.STONE)
                helper.setBlock(BlockPos(7, 1, 0), Blocks.GOLD_ORE)
                fun position(x: Int): NpcBlockPosition {
                    val p = helper.absolutePos(BlockPos(x, 1, 0))
                    return NpcBlockPosition(p.x, p.y, p.z)
                }
                val request = OperationWorldRequest(blocks = listOf(position(4), position(7)))
                other.player.teleportTo(helper.level, arena.start.x + 2, arena.start.y, arena.start.z + 2, 0F, 0F)
                other.player.setGameMode(GameType.CREATIVE)
                fun capture(): OperationWorldInspection {
                    val reply = OperationInspectionApi.inspect(server, actor.player, npc.npcUuid, request)
                    check(reply.result.status == NpcActionStatus.SUCCEEDED)
                    val inspection = checkNotNull(reply.inspection)
                    val world = checkNotNull(inspection.world)
                    check(world.observedTick == inspection.physical.gameTime && world.observedTick == inspection.body.observedTick)
                    check(world.source == OperationObservationSource.NPC_VISUAL_SENSOR)
                    check(world.dimensionId == inspection.physical.dimensionId)
                    return world
                }
                fun players(world: OperationWorldInspection) =
                    (world.entities as NpcVisualEntityScan.Observed).entities.filter { it.isPlayer }.map { it.uuid }
                val first = capture()
                check(other.player.uuid in players(first) && actor.player.uuid !in players(first))
                val visible = first.blocks[0].observation as NpcVisualBlockRead.Observed
                check(visible.block.blockId == "minecraft:diamond_ore")
                check(first.blocks[1].observation == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.NOT_OBSERVED))
                other.player.isInvisible = true
                check(other.player.uuid !in players(capture()))
                other.player.isInvisible = false
                other.player.setGameMode(GameType.SPECTATOR)
                check(other.player.uuid !in players(capture()))
                arena.body.addEffect(MobEffectInstance(MobEffects.BLINDNESS, 200))
                val blind = capture()
                check(blind.entities == NpcVisualEntityScan.Unavailable(NpcVisualUnavailableReason.BLINDED))
                check(blind.blocks.all { it.observation == NpcVisualBlockRead.Unavailable(NpcVisualUnavailableReason.BLINDED) })
                check(other.player.uuid in players(first) && visible.block.blockId == "minecraft:diamond_ore")
                val denied = OperationInspectionApi.inspect(server, other.player, npc.npcUuid, request)
                check(denied.result.code == NpcActionCode.PERMISSION_DENIED && denied.inspection == null)
            } finally { arena.close(); actor.close(); other.close() }
            helper.succeed()
        }
    }
}
