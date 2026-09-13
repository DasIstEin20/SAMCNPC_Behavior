package io.samcnpc.behavior.client

import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.operations.*
import io.samcnpc.behavior.task.*
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.model.HumanoidModel
import net.minecraft.client.model.PlayerModel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.world.Difficulty
import net.minecraft.world.item.UseAnim
import net.minecraft.world.level.GameRules
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLivingEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.atan2
import kotlin.math.hypot

private data class OperationRenderView(val index: Int,val entityId: Int,val label: String)

/** One client world covers the grouped gameplay cases, with immutable render/server handoff. */
@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID,value=[Dist.CLIENT])
object OperationsClientSmoke {
    private val enabled=java.lang.Boolean.getBoolean("samcnpc.operationsClient")
    private val kinds=listOf(OperationKind.MINING,OperationKind.FARM,OperationKind.PLANTING,OperationKind.WOOD_REPLANT,
        OperationKind.TRANSPORT,OperationKind.INVENTORY,OperationKind.FOOD,OperationKind.PATROL,OperationKind.DEFEND)
    private val total=kinds.size+TacticalProbe.entries.size
    @Volatile private var worldId: String?=null
    @Volatile private var view: OperationRenderView?=null
    @Volatile private var outcome: String?=null
    private val screenBits=AtomicInteger(0)
    private val requested=BooleanArray(total)
    private val frames=IntArray(total)
    private val walking=IntArray(total)
    private val using=IntArray(total)
    private var clientTicks=0
    private var done=false
    // These are accessed only by the integrated server thread.
    private var index=0
    private var stageTicks=0
    private var scene: OperationScene?=null
    private var tactical: OperationTacticalScene?=null
    private var supervision: OperationSupervisionProbe?=null
    private var assigned=false
    private var result: String?=null
    private var completeTicks=0
    private val results=mutableListOf<String>()

    @SubscribeEvent fun clientTick(event: TickEvent.ClientTickEvent) {
        if(!enabled || done || event.phase != TickEvent.Phase.END) return
        val minecraft=Minecraft.getInstance()
        try {
            check(++clientTicks <= 30000) { "Grouped operations client timed out at ${view?.label}" }
            if(minecraft.screen is AccessibilityOnboardingScreen && minecraft.overlay == null) minecraft.screen?.onClose()
            if(worldId == null && minecraft.screen is TitleScreen && minecraft.overlay == null) {
                val id="operations-client-${System.currentTimeMillis()}"; worldId=id
                minecraft.options.pauseOnLostFocus=false; minecraft.options.hideGui=true
                minecraft.options.renderDistance().set(6); minecraft.options.simulationDistance().set(8); minecraft.options.framerateLimit().set(60)
                val settings=LevelSettings(id,GameType.CREATIVE,false,Difficulty.NORMAL,true,GameRules(),WorldDataConfiguration.DEFAULT)
                minecraft.createWorldOpenFlows().createFreshLevel(id,settings,WorldOptions(0L,false,false),
                    { registry -> registry.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions() })
            }
            val current=view
            val body=current?.let { minecraft.level?.getEntity(it.entityId) }
            val player=minecraft.player
            if(body != null && player != null) {
                val dx=body.x-player.x; val dz=body.z-player.z
                player.yRot=(Math.toDegrees(atan2(dz,dx))-90.0).toFloat()
                player.xRot=(-Math.toDegrees(atan2(body.eyeY-player.eyeY,hypot(dx,dz)))).toFloat()
                val at=current.index
                val witnessed=if(at >= kinds.size) using[at] >= 2 else if(kinds[at] == OperationKind.DEFEND) frames[at] >= 2 else walking[at] >= 2
                if(witnessed && !requested[at]) {
                    requested[at]=true
                    Screenshot.grab(minecraft.gameDirectory,"operations-${current.label.lowercase()}.png",minecraft.mainRenderTarget) {
                        screenBits.getAndUpdate { bits -> bits or (1 shl at) }
                    }
                }
            }
            val finished=outcome ?: return
            check(!finished.startsWith("FAIL")) { finished }
            check(frames.all { it >= 2 }) { "Some operation NPCs were never rendered" }
            check(kinds.indices.filter { kinds[it] != OperationKind.DEFEND }.all { walking[it] >= 2 }) { "Some work routes were not rendered walking" }
            check((kinds.size until total).all { using[it] >= 2 }) { "Bow/drink/shield animation was not rendered" }
            check(screenBits.get() == (1 shl total)-1) { "Missing actual operation screenshots" }
            Files.writeString(Path.of("operations-client.txt"),"PASS cases=$total actual_world_effects=true\n$finished\nframes=${frames.toList()} walking=${walking.toList()} uses=${using.toList()} screenshots=$total\n")
            done=true; minecraft.stop()
        } catch(error: Exception) {
            Files.writeString(Path.of("operations-client.txt"),"FAIL ${error.stackTraceToString()}\n")
            done=true; minecraft.stop()
        }
    }
    @SubscribeEvent fun serverTick(event: TickEvent.ServerTickEvent) {
        if(!enabled || outcome != null || event.phase != TickEvent.Phase.END) return
        val id=worldId ?: return
        val server=event.server
        if(server.getWorldPath(LevelResource.ROOT).normalize().fileName.toString() != id) return
        val player=server.playerList.players.firstOrNull() ?: return
        try {
            if(scene == null) {
                OperationCases.configure(server)
                val origin=BlockPos(400+index%4*64,80,400+index/4*64)
                val kind=kinds.getOrNull(index) ?: OperationKind.ATTACK
                val next=OperationScene.create(server.overworld(),kind,origin)
                scene=next; assigned=false; result=null; stageTicks=0; completeTicks=0; supervision=null
                tactical=if(index >= kinds.size) OperationTacticalScene(next,TacticalProbe.entries[index-kinds.size]) else null
                player.setGameMode(GameType.SPECTATOR)
                player.teleportTo(next.level,origin.x+10.5,origin.y+9.0,origin.z+16.5,0.0F,25.0F)
                view=null
            }
            val current=checkNotNull(scene)
            check(++stageTicks < 7000) { "${view?.label} timed out: ${TaskService.status(server,current.npcId)}" }
            if(!current.loaded) { check(stageTicks < 600) { "New scene body did not become live" }; return }
            if(view == null) view=OperationRenderView(index,current.body.id,if(index < kinds.size) current.kind.name else TacticalProbe.entries[index-kinds.size].name)
            if(result == null) {
                val combat=tactical
                if(combat != null) { combat.tick(); result=combat.outcome }
                else {
                    if(!assigned) {
                        if(!current.body.onGround()) return
                        OperationCases.prepare(current); assigned=true
                    }
                    val probe=supervision ?: OperationSupervisionProbe(server,player,current.npcId).also { supervision=it }
                    if(!probe.tick()) return
                    val record=current.record
                    check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "${current.kind}: ${record.report()}" }
                    OperationResourceCases.advanceWorldInput(current)
                    if(record.status.terminal) {
                        OperationCases.verify(current)
                        result="${current.kind} physical_effects=true public_controls=true public_amendments=true remaining=${record.primary.remainingTicks} task=${record.id}"
                    }
                }
            }
            val evidence=result ?: return
            if(++completeTicks < 40 || screenBits.get() and (1 shl index) == 0) return
            results.add(evidence)
            Files.writeString(Path.of("operations-client.txt"),"RUNNING completed=${results.size}/$total\n${results.joinToString("\n")}\n")
            if(results.size == total) outcome=results.joinToString("\n")
            else { current.close(); scene=null; tactical=null; view=null; index++ }
        } catch(error: Exception) {
            outcome="FAIL stage=$index completed=${results.size}\n${results.joinToString("\n")}\n${error.stackTraceToString()}"
            com.mojang.logging.LogUtils.getLogger().error("Grouped client operations failed",error)
        }
    }
    @SubscribeEvent fun rendered(event: RenderLivingEvent.Post<*,*>) {
        if(!enabled || done) return
        val current=view ?: return
        if(event.entity.id != current.entityId) return
        val model=event.renderer.model as? PlayerModel<*> ?: error("Operations NPC lost its player model")
        frames[current.index]++
        if(kotlin.math.abs(model.rightLeg.xRot) > 0.1F) walking[current.index]++
        val item=event.entity.useItem
        val observed=when(current.label) {
            "RANGED" -> model.rightArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW || model.leftArmPose == HumanoidModel.ArmPose.BOW_AND_ARROW
            "HEALING" -> event.entity.isUsingItem && item.useAnimation == UseAnim.DRINK
            "SHIELD" -> event.entity.isUsingItem && item.useAnimation == UseAnim.BLOCK
            else -> false
        }
        if(observed) using[current.index]++
    }
}
