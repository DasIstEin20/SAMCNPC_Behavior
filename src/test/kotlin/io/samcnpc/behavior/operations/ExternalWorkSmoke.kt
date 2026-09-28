package io.samcnpc.behavior.operations

import com.google.gson.GsonBuilder
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.behavior.task.*
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** D/E/G external rule-pack cases use public assignment once, then only observe or inject declared faults. */
@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object ExternalWorkSmoke {
    private val mode=System.getProperty("samcnpc.externalWork")
    private val scenes=mutableListOf<OperationScene>()
    private var actor: OperationActor?=null
    private var enemy: LivingEntity?=null
    private var ticks=0;private var started=false;private var done=false
    private var injected=false;private var combatObserved=false;private var waitTicks=0
    private var pauseTick=0;private var pausedBalance=0
    private val identities=mutableMapOf<UUID,UUID>()
    private val selected=mutableSetOf<String>()
    private val rows=mutableListOf<Map<String,Any?>>()
    private val gson=GsonBuilder().setPrettyPrinting().create()

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if(mode==null)return
        guarded(event.server) {
            OperationCases.configure(event.server);check(BehaviorRuntimeService.reload().accepted)
            actor=OperationActor(event.server.overworld(),UUID.nameUUIDFromBytes("external-work-actor".toByteArray()))
            scenes.add(OperationScene.create(event.server.overworld(),OperationKind.TRANSPORT,BlockPos(0,64,0)))
            if(mode.startsWith("couriers"))scenes.add(OperationScene.create(event.server.overworld(),OperationKind.TRANSPORT,BlockPos(0,64,0),20.5,0.5))
            checkNotNull(actor).approach(scenes.first());write("RUNNING")
        }
    }
    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if(mode==null || done || scenes.isEmpty() || event.phase!=TickEvent.Phase.END)return
        guarded(event.server) {
            check(++ticks<5000) { "fixed5000 tick budget exceeded" }
            if(scenes.any { !it.loaded || !it.body.onGround() })return@guarded
            if(!started) { prepare();started=true }
            for(s in scenes) {
                check(s.record.id==identities[s.npcId]) { "task identity changed/repeated assignment" }
                selected.addAll(BehaviorRuntimeService.diagnostic(s.npcId)?.selectedIntents.orEmpty())
            }
            when {
                mode.startsWith("machine") -> machine()
                mode.startsWith("couriers") -> couriers()
                mode.startsWith("guard") -> guard()
            }
            if(ticks%100==0) {
                rows.add(mapOf("tick" to ticks,"tasks" to scenes.map { mapOf("npc" to it.npcId,"task" to it.record.id,"position" to it.npc.snapshot().position,"report" to it.record.report(),"diagnostic" to BehaviorRuntimeService.diagnostic(it.npcId)) }))
                write("RUNNING")
            }
        }
    }
    private fun prepare() {
        val s=scenes.first();val dimension=s.npc.snapshot().dimensionId
        when {
            mode?.startsWith("machine")==true -> {
                s.level.setBlock(s.pos(10,1,0),Blocks.FURNACE.defaultBlockState(),3)
                s.give(Items.RAW_IRON,4);s.give(Items.COAL,1)
                val definition=MachineTaskDefinition(dimension,MachineFeeds(listOf(
                    MachinePort(NpcContainerEndpoint(dimension,s.block(10,1,0),NpcBlockFace.UP),0,"minecraft:raw_iron",4),
                    MachinePort(NpcContainerEndpoint(dimension,s.block(10,1,0),NpcBlockFace.NORTH),0,"minecraft:coal",1))),
                    MachinePort(NpcContainerEndpoint(dimension,s.block(10,1,0),NpcBlockFace.DOWN),0,"minecraft:iron_ingot",4),s.start,returnTo=s.start,pollTicks=10,budget=TaskBudget(3000))
                assign(s,definition,"acceptance:g_machine")
            }
            mode?.startsWith("couriers")==true -> {
                s.makeChest(3,2).setItem(0,ItemStack(Items.COBBLESTONE,if(mode=="couriers_partial")17 else 32));s.makeChest(20,2)
                for(x in 6..16)for(z in listOf(-1,1))for(y in 1..2)s.level.setBlock(s.pos(x,y,z),Blocks.STONE.defaultBlockState(),3)
                for(scene in scenes)assign(scene,TransportTaskDefinition(dimension,s.choices(3,2),s.choices(20,2),"minecraft:cobblestone",16,s.start,returnTo=s.start,budget=TaskBudget(2800)),"acceptance:d_courier")
                check(identities.values.distinct().size==2)
            }
            mode?.startsWith("guard")==true -> {
                s.give(Items.IRON_AXE);s.makeChest(0,3)
                for(y in 1..4)s.level.setBlock(s.pos(10,y,0),Blocks.OAK_LOG.defaultBlockState(),3)
                assign(s,LumberjackTaskDefinition(dimension,s.area(8,12,5,-2,2),WoodSelection(listOf("samcnpc:oak")),s.block(0,1,3),4,budget=TaskBudget(3000),version=2),"acceptance:b_lumberjack")
                val player=checkNotNull(actor).player
                check(s.server.commands.performPrefixedCommand(player.createCommandSourceStack(),"samcnpc behavior task reaction ${s.npcId} retaliate")==1)
                enemy=s.enemy(EntityType.HUSK,8,3)
            }
        }
    }
    private fun assign(s: OperationScene,definition: TaskDefinition,pack: String) {
        val player=checkNotNull(actor).player;checkNotNull(actor).approach(s)
        val order=checkNotNull(OperationPublicAssignmentProof.order(definition))
        val view=checkNotNull(OperationSupervisionApi.observe(s.server,player,s.npcId).observation)
        val result=OperationSupervisionApi.assign(s.server,player,s.npcId,OperationAssignmentRequest(view.task?.taskId,view.observedTick,view.observedTick+20,order))
        check(result.result.status==NpcActionStatus.SUCCEEDED) { result.result.detail }
        identities[s.npcId]=checkNotNull(result.observation?.task).taskId
        val assigned=(BehaviorRuntimeService.assignedPacks(s.server,s.npcId)+pack).distinct().joinToString(",")
        check(s.server.commands.performPrefixedCommand(player.createCommandSourceStack(),"samcnpc behavior assign ${s.npcId} $assigned")==1)
    }
    private fun machine() {
        val s=scenes.first();val state=checkNotNull(s.record.primary.machine)
        if(state.phase==MachinePhase.WORK && state.collected<4)waitTicks++
        if(mode=="machine_cancel" && waitTicks>=40 && pauseTick==0) {
            pauseTick=ticks;pausedBalance=s.carried("minecraft:iron_ingot")
            check(s.server.commands.performPrefixedCommand(checkNotNull(actor).player.createCommandSourceStack(),"samcnpc behavior task cancel ${s.npcId}")==1)
        }
        if(pauseTick>0) {
            check(s.record.status==TaskStatus.CANCELLED && s.carried("minecraft:iron_ingot")==pausedBalance)
            if(ticks>=pauseTick+240)finish("PASS_MACHINE_CANCEL_NO_LATE_PAYOUT")
            return
        }
        if(s.record.status.terminal) {
            check(s.record.status==TaskStatus.COMPLETED && waitTicks>=100) { s.record.report().toString() }
            check(state.collected==4 && state.supplied.toList()==listOf(4,1) && s.carried("minecraft:iron_ingot")==4)
            check(s.carried("minecraft:raw_iron")==0 && s.carried("minecraft:coal")==0);s.requireReturned();finish("PASS_MACHINE_REAL_WAIT")
        }
    }
    private fun couriers() {
        val s=scenes.first();val total=if(mode=="couriers_partial")17 else 32
        val source=s.count(3,2,Items.COBBLESTONE);val output=s.count(20,2,Items.COBBLESTONE)
        check(source+output+scenes.sumOf { it.carried("minecraft:cobblestone") }==total) { "item conservation violated" }
        if(scenes.all { it.record.status.terminal }) {
            val ledgers=scenes.map { checkNotNull(it.record.primary.transport).ledger }
            check(ledgers.all { it.valid() } && ledgers.sumOf { it.withdrawn }==total && ledgers.sumOf { it.delivered }==output)
            if(mode=="couriers_partial")check(scenes.any { it.record.status!=TaskStatus.COMPLETED } && output==17)
            else check(scenes.all { it.record.status==TaskStatus.COMPLETED } && output==32)
            finish(if(mode=="couriers_partial")"PASS_HONEST_PARTIAL_CONSERVATION" else "PASS_SHARED_COURIERS")
        }
    }
    private fun guard() {
        val s=scenes.first();val hostile=checkNotNull(enemy)
        if(!injected && s.npc.snapshot().blockBreak!=null) {
            hostile.moveTo(s.body.x,s.body.y,s.body.z+3)
            check(s.body.hurt(s.level.damageSources().mobAttack(hostile),1F));injected=true
            rows.add(mapOf("fixtureMutation" to "eligible hostile deals1 damage during wood work","tick" to ticks))
        }
        if(s.record.frames.size==2)combatObserved=true
        if(mode=="guard_pause" && combatObserved && pauseTick==0) {
            pauseTick=ticks;pausedBalance=s.count(0,3,Items.OAK_LOG)
            check(s.server.commands.performPrefixedCommand(checkNotNull(actor).player.createCommandSourceStack(),"samcnpc behavior task pause ${s.npcId}")==1)
        }
        if(pauseTick>0) {
            check(s.record.status==TaskStatus.PAUSED && s.count(0,3,Items.OAK_LOG)==pausedBalance)
            if(ticks>=pauseTick+60)finish("PASS_MANUAL_PAUSE_OVERRIDES_COMBAT")
            return
        }
        if(s.record.status.terminal) {
            check(s.record.status==TaskStatus.COMPLETED && injected && combatObserved && !hostile.isAlive)
            check(s.count(0,3,Items.OAK_LOG)==4 && s.record.completedInterruptions==1)
            check(checkNotNull(actor).player.health==checkNotNull(actor).player.maxHealth)
            finish("PASS_GUARD_RESUMES_EXACT_WORK")
        }
    }
    private fun finish(result:String) {
        check(selected.any { it.startsWith("acceptance:") }) { "External pack never won a channel" }
        done=true;write(result);for(s in scenes)s.close();actor?.close()
        scenes.first().server.saveEverything(false,true,true);scenes.first().server.halt(false)
    }
    private fun write(result:String,error:String?=null) {
        Files.writeString(Path.of("external-mission-result.txt"),"$result ${error.orEmpty()}\n")
        Files.writeString(Path.of("external-mission-result.json"),gson.toJson(mapOf("result" to result,"mode" to mode,"ticks" to ticks,"seed" to 20260928,"taskIdentities" to identities,"selected" to selected,"waitTicks" to waitTicks,"error" to error,"observations" to rows)))
    }
    private fun guarded(server:MinecraftServer,action:()->Unit) {
        try { action() } catch(error:Exception) {
            done=true;write("FAIL",error.stackTraceToString());com.mojang.logging.LogUtils.getLogger().error("External work proof failed",error)
            actor?.close();server.halt(false)
        }
    }
}
