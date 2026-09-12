package io.samcnpc.behavior.gametest

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import java.util.UUID

/** A real server-side player connection for permission/control integration tests. */
internal class GameTestActor(level: ServerLevel, name: String) {
    private val connection = Connection(PacketFlow.SERVERBOUND)
    private val channel = EmbeddedChannel(connection)
    val player = ServerPlayer(level.server, level, GameProfile(UUID.randomUUID(), name))
    init {
        connection.setProtocol(ConnectionProtocol.PLAY)
        level.server.playerList.placeNewPlayer(connection, player)
        player.setGameMode(GameType.SPECTATOR)
    }
    fun close() { player.server.playerList.remove(player); channel.finishAndReleaseAll() }
}
