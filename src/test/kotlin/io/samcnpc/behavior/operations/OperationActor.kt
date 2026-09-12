package io.samcnpc.behavior.operations

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import java.util.UUID

/** Isolated permission fixture, never evidence of an authenticated account or skin session. */
internal class OperationActor(level: ServerLevel, id: UUID) {
    private val connection=Connection(PacketFlow.SERVERBOUND)
    private val channel=EmbeddedChannel(connection)
    val player=ServerPlayer(level.server,level,GameProfile(id,"O4Permission"))
    init {
        connection.setProtocol(ConnectionProtocol.PLAY)
        level.server.playerList.placeNewPlayer(connection,player)
        player.setGameMode(GameType.SPECTATOR)
        level.server.playerList.op(player.gameProfile)
    }
    fun approach(s: OperationScene) = player.teleportTo(s.level,s.body.x,s.body.y+4,s.body.z+5,0.0F,0.0F)
    fun close() { player.server.playerList.remove(player); channel.finishAndReleaseAll() }
}
