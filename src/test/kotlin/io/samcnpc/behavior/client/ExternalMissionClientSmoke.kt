package io.samcnpc.behavior.client

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.MissionApi
import io.samcnpc.behavior.mission.MissionState
import io.samcnpc.behavior.mission.MissionStore
import io.samcnpc.behavior.operations.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.NpcActionStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.model.PlayerModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.atan2
import kotlin.math.hypot

/** Actual client rendering of the same Studio bundle, using the real integrated-server player for admission. */
@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID,value=[Dist.CLIENT])
object ExternalMissionClientSmoke {
    private val enabled=java.lang.Boolean.getBoolean("samcnpc.externalMissionClient")
    @Volatile private var worldId:String?=null
    @Volatile private var entityId=-1
    @Volatile private var outcome:String?=null
    @Volatile private var screenshot=false
    private var scene:OperationScene?=null
    private var assigned=false;private var ticks=0;private var done=false;private var requested=false
    private var frames=0;private var walking=0;private var equipped=0
    @SubscribeEvent fun clientTick(event:TickEvent.ClientTickEvent) {
        if(!enabled || done || event.phase!=TickEvent.Phase.END)return
        val mc=Minecraft.getInstance()
        try {
            check(++ticks<6000) { "external mission client timeout" }
            if(mc.screen is AccessibilityOnboardingScreen && mc.overlay==null)mc.screen?.onClose()
            if(worldId==null && mc.screen is TitleScreen && mc.overlay==null) {
                val id="external-mission-client-${System.currentTimeMillis()}";worldId=id
                mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;mc.options.framerateLimit().set(60)
                mc.options.gamma().set(1.0)
                mc.options.renderDistance().set(6);mc.options.simulationDistance().set(6)
                val settings=LevelSettings(id,GameType.CREATIVE,false,Difficulty.NORMAL,true,GameRules(),WorldDataConfiguration.DEFAULT)
                mc.createWorldOpenFlows().createFreshLevel(id,settings,WorldOptions(20260928L,false,false),
                    { registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
            }
            val body=mc.level?.getEntity(entityId);val player=mc.player
            if(body!=null && player!=null) {
                val dx=body.x-player.x;val dz=body.z-player.z
                player.yRot=(Math.toDegrees(atan2(dz,dx))-90.0).toFloat()
                player.xRot=(-Math.toDegrees(atan2(body.eyeY-player.eyeY,hypot(dx,dz)))).toFloat()
            }
            if(walking>=10 && equipped>=10 && frames>=150 && !requested) {
                requested=true;Screenshot.grab(mc.gameDirectory,"external-mission-walking.png",mc.mainRenderTarget) { screenshot=true }
            }
            val result=outcome ?: return
            check(result.startsWith("PASS")) { result }
            if(!screenshot)return
            check(frames>0 && walking>=2 && equipped>=2)
            Files.writeString(Path.of("external-mission-client.txt"),"PASS actual_client=true frames=$frames walking=$walking equipped=$equipped\n$result\n")
            done=true;mc.stop()
        } catch(error:Exception) {
            Files.writeString(Path.of("external-mission-client.txt"),"FAIL ${error.stackTraceToString()}")
            done=true;mc.stop()
        }
    }
    @SubscribeEvent fun serverTick(event:TickEvent.ServerTickEvent) {
        if(!enabled || outcome!=null || worldId==null || event.phase!=TickEvent.Phase.END)return
        val server=event.server;val player=server.playerList.players.firstOrNull() ?: return
        try {
            var s=scene
            if(s==null) {
                OperationCases.configure(server)
                check(BehaviorRuntimeService.reload().accepted)
                s=OperationScene.create(server.overworld(),OperationKind.PREPARATION,BlockPos(0,64,0));scene=s
                // Lighting is initial fixture geometry, not a later NPC effect or edited screenshot.
                for(x in -2..10 step 3) for(z in -2..4 step 3) {
                    server.overworld().setBlock(BlockPos(x,64,z),net.minecraft.world.level.block.Blocks.SEA_LANTERN.defaultBlockState(),3)
                }
                player.setGameMode(GameType.SPECTATOR);player.teleportTo(server.overworld(),5.5,68.0,6.5,0F,0F)
                entityId=s.body.id
            }
            if(!s.loaded || !s.body.onGround())return
            if(!assigned) {
                s.makeChest(3,0).setItem(0,ItemStack(Items.IRON_AXE));assigned=true
                check(MissionApi.start(server,player,s.npcId,"acceptance:tutorial").status==NpcActionStatus.SUCCEEDED)
            }
            val record=checkNotNull(MissionStore.forServer(server).get(s.npcId))
            check(record.state!=MissionState.REVIEW_REQUIRED) { record.detail }
            if(record.state==MissionState.COMPLETED) {
                s.requireReturned();check(s.count(3,0,Items.IRON_AXE)==0 && s.carried("minecraft:iron_axe")==1)
                check(record.confirmed.size==3 && record.confirmed.values.distinct().size==3)
                outcome="PASS physical mission=${record.id} stages=${record.confirmed.keys}"
            }
        } catch(error:Exception) { outcome="FAIL ${error.stackTraceToString()}" }
    }
    @SubscribeEvent fun rendered(event:RenderLivingEvent.Post<*,*>) {
        if(!enabled || done || event.entity.id!=entityId)return
        val model=event.renderer.model as? PlayerModel<*> ?: return
        frames++;if(kotlin.math.abs(model.rightLeg.xRot)>0.1F)walking++
        if(event.entity.mainHandItem.`is`(Items.IRON_AXE))equipped++
    }
}
