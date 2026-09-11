package io.samcnpc.behavior.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import io.samcnpc.behavior.SamcnpcBehavior
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.GameType
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

@GameTestHolder(SamcnpcBehavior.MOD_ID)
@PrefixGameTestTemplate(false)
object TaskCombatWorkGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2450, batch = "task_combat_work_resume")
    fun continuousDamageInterruptsActualMiningOnceAndResumesTheSameTaskToDelivery(helper: GameTestHelper) = exercise(helper, TaskCombatWorkMode.MINING)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2450, batch = "task_combat_navigation_resume")
    fun combatInterruptsAnActualApproachAndResumesWoodDelivery(helper: GameTestHelper) = exercise(helper, TaskCombatWorkMode.NAVIGATION)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2450, batch = "task_combat_transfer_resume")
    fun combatBetweenPhysicalTransfersRetainsBothReceiptsAndFinishesDelivery(helper: GameTestHelper) = exercise(helper, TaskCombatWorkMode.TRANSFER)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2450, batch = "task_combat_scaffold_resume")
    fun combatDuringActualScaffoldJumpResumesAndCleansItsSupports(helper: GameTestHelper) = exercise(helper, TaskCombatWorkMode.SCAFFOLD)

    private fun exercise(helper: GameTestHelper, mode: TaskCombatWorkMode) {
        // Vanilla's helper supplies a Connection without a channel; Forge queries its pipeline.
        // A real in-memory Netty channel supplies that fixture boundary without a network socket.
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "TaskCombatTest"))
        helper.level.server.playerList.placeNewPlayer(connection, player)
        player.setGameMode(GameType.SPECTATOR)
        val scenario = TaskCombatWorkScenario(helper.level, player, helper.absolutePos(BlockPos.ZERO), mode)
        var done = false
        helper.onEachTick {
            if (done) return@onEachTick
            scenario.tick()
            if (scenario.outcome != null) {
                done = true
                scenario.close()
                helper.level.server.playerList.remove(player)
                channel.finishAndReleaseAll()
                helper.succeed()
            }
        }
    }
}
