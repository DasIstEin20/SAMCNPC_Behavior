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
object RetaliationWorkGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 1100, batch = "retaliation_damage_lifecycle")
    fun retaliationSurvivesContinuousDamageButEndsAtLeashReloadAndUnassignment(helper: GameTestHelper) {
        // Vanilla's helper supplies a Connection without a channel; Forge queries its pipeline.
        // A real in-memory Netty channel supplies that fixture boundary without a network socket.
        val connection = Connection(PacketFlow.SERVERBOUND)
        val channel = EmbeddedChannel(connection)
        connection.setProtocol(ConnectionProtocol.PLAY)
        val player = ServerPlayer(helper.level.server, helper.level, GameProfile(UUID.randomUUID(), "RetaliationTest"))
        helper.level.server.playerList.placeNewPlayer(connection, player)
        player.setGameMode(GameType.SPECTATOR)
        val scenario = RetaliationWorkScenario(helper.level, player, helper.absolutePos(BlockPos.ZERO))
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
