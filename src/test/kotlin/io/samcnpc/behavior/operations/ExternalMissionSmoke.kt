package io.samcnpc.behavior.operations

import com.google.gson.GsonBuilder
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.api.*
import io.samcnpc.behavior.mission.*
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import io.samcnpc.core.api.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Fixtures create initial stock only. Every later transfer/equip/move uses mission admission and normal rules. */
@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object ExternalMissionSmoke {
    private val mode=System.getProperty("samcnpc.externalMission")
    private val full=mode?.startsWith("full") == true
    private val missionName=if(full) "acceptance:full" else "acceptance:tutorial"
    private var scene: OperationScene?=null
    private var actor: OperationActor?=null
    private var ticks=0
    private var done=false
    private var started=false
    private var pausedAt=0
    private var taskAtPause: UUID?=null
    private var budgetAtPause=0
    private var activeTicks=0
    private var round=1
    private var roundStarted=0
    private val startedNanos=System.nanoTime()
    private var visited=linkedSetOf<String>()
    private val rows=mutableListOf<Map<String,Any?>>()
    private var expected=CompoundTag()
    private val checkpoint=Path.of("mission-checkpoint.nbt")
    private val gson=GsonBuilder().setPrettyPrinting().create()

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if(mode == null) return
        guarded(event.server) {
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            OperationCases.configure(event.server)
            check(BehaviorRuntimeService.reload().accepted) { "External Studio bundle failed actual runtime reload" }
            check(missionName in MissionApi.availableIds())
            if(mode=="loader") {
                val cases=ExternalReloadProof.run()
                rows.addAll(cases);done=true;write("PASS_NEGATIVE_RELOAD");event.server.halt(false);return@guarded
            }
            if(mode == "load" || mode?.endsWith("_load") == true) {
                expected=NbtIo.readCompressed(checkpoint.toFile())
                check(expected.getLong("pid") != ProcessHandle.current().pid())
                actor=OperationActor(event.server.overworld(),expected.getUUID("actor"))
                scene=OperationScene.load(event.server.overworld(),expected.getCompound("scene"))
                actor?.player?.teleportTo(event.server.overworld(),0.5,69.0,5.5,0F,0F)
                started=true
            } else {
                check(!Files.exists(checkpoint)) { "Use a fresh campaignId for save mode" }
                actor=OperationActor(event.server.overworld(),UUID.nameUUIDFromBytes("external-mission-actor".toByteArray()))
                scene=OperationScene.create(event.server.overworld(),OperationKind.PREPARATION,BlockPos(0,64,0))
                checkNotNull(actor).approach(checkNotNull(scene))
            }
            write("RUNNING")
        }
    }

    @SubscribeEvent fun tick(event: TickEvent.ServerTickEvent) {
        if(mode == null || scene == null || done || event.phase != TickEvent.Phase.END) return
        guarded(event.server) {
            ticks++; check(ticks < if(mode=="full_soak") 72000 else if(full) 40000 else 3000) { "campaign exceeded fixed tick budget" }
            if(mode=="full_soak") check(ticks-roundStarted < 12000) { "full mission soak round stalled" }
            val s=checkNotNull(scene)
            if(!s.loaded || !s.body.onGround()) return@guarded
            val a=checkNotNull(actor)
            if(!started) {
                started=true
                val chest=s.makeChest(3,0)
                val worn=ItemStack(Items.STONE_AXE);worn.damageValue=worn.maxDamage-1
                chest.setItem(0,worn);chest.setItem(1,ItemStack(Items.IRON_AXE));chest.setItem(2,ItemStack(Items.CHARCOAL,11))
                if(full) prepareFull(s)
                if(mode=="pack_only") {
                    check(event.server.commands.performPrefixedCommand(a.player.createCommandSourceStack(),"samcnpc behavior assign ${s.npcId} acceptance:quartermaster")==1)
                } else {
                    val result=MissionApi.start(event.server,a.player,s.npcId,missionName)
                    check(result.status==NpcActionStatus.SUCCEEDED) { result.detail }
                }
            }
            if(mode=="pack_only") {
                check(io.samcnpc.behavior.task.TaskStore.forServer(event.server).get(s.npcId)==null)
                check(s.carried("minecraft:iron_axe")==0 && s.count(3,0,Items.IRON_AXE)==1)
                if(ticks>=60) finish(event.server,"PASS_PACK_IS_NOT_TASK_CONSTRUCTOR")
                return@guarded
            }
            if(mode=="corrupt" && pausedAt>0) {
                check(s.record.status==io.samcnpc.behavior.task.TaskStatus.PAUSED) { "Corrupt mission storage allowed its operation to keep running: ${s.record.status}" }
                if(ticks>=pausedAt+40) finish(event.server,"PASS_CORRUPT_STORE_SAFE_IDLE")
                return@guarded
            }
            val record=checkNotNull(MissionStore.forServer(event.server).get(s.npcId))
            if(record.state==MissionState.RUNNING) activeTicks++
            if(mode=="full_soak" && round%2==0 && record.stageId=="wood" && record.state==MissionState.RUNNING && pausedAt==0) {
                check(MissionApi.control(event.server,a.player,s.npcId,MissionControl.PAUSE).status==NpcActionStatus.SUCCEEDED)
                pausedAt=ticks;taskAtPause=record.taskId;budgetAtPause=record.remaining
            }
            if(mode=="full_soak" && pausedAt>0 && ticks<=pausedAt+40) {
                check(record.state==MissionState.PAUSED && record.remaining==budgetAtPause && record.taskId==taskAtPause)
                if(ticks==pausedAt+40) check(MissionApi.control(event.server,a.player,s.npcId,MissionControl.RESUME).status==NpcActionStatus.SUCCEEDED)
                return@guarded
            }
            if(mode=="corrupt" && record.stageId=="travel" && record.state==MissionState.RUNNING) {
                val bad=CompoundTag();bad.putInt("version",999)
                event.server.overworld().dataStorage.set(MissionStore.DATA_NAME,MissionStore.load(bad))
                pausedAt=ticks
                rows.add(mapOf("fixtureMutation" to "replace mission SavedData with unsupported version", "tick" to ticks))
                return@guarded
            }
            if(mode in setOf("pack_changed","pack_removed") && record.stageId=="travel" && record.state==MissionState.RUNNING && pausedAt==0) {
                val zip=Path.of("resources/samcnpc/behaviors/tutorial.zip")
                val entries=linkedMapOf<String,ByteArray>()
                java.util.zip.ZipInputStream(Files.newInputStream(zip)).use { input ->
                    while(true) { val entry=input.nextEntry ?: break;entries[entry.name]=input.readAllBytes() }
                }
                if(mode=="pack_removed") Files.move(zip,zip.resolveSibling("tutorial.disabled")) else {
                    val path=entries.keys.first { it.startsWith("behaviors/") }
                    val document=com.google.gson.JsonParser.parseString(checkNotNull(entries[path]).toString(Charsets.UTF_8)).asJsonObject
                    document.addProperty("description","Deliberately changed during active mission")
                    entries[path]=document.toString().toByteArray()
                    java.util.zip.ZipOutputStream(Files.newOutputStream(zip)).use { output ->
                        for((name,bytes) in entries) { output.putNextEntry(java.util.zip.ZipEntry(name));output.write(bytes);output.closeEntry() }
                    }
                }
                check(BehaviorRuntimeService.reload().accepted)
                pausedAt=ticks;taskAtPause=record.taskId
                rows.add(mapOf("fixtureMutation" to mode,"tick" to ticks));return@guarded
            }
            if(mode in setOf("pack_changed","pack_removed") && pausedAt>0) {
                check(record.state==MissionState.REVIEW_REQUIRED && record.taskId==taskAtPause)
                check(s.record.status==io.samcnpc.behavior.task.TaskStatus.PAUSED)
                if(ticks>=pausedAt+40) finish(event.server,"PASS_CHANGED_PACK_REQUIRES_REVIEW")
                return@guarded
            }
            if(mode in setOf("boundary_load","full_boundary_load","receipt_load","pause_load","cancel_load")) {
                if(record.restored && record.state==MissionState.READY) {
                    check(ticks<200) { "NPC did not receive its first reconciliation tick" };return@guarded
                }
                check(record.id==expected.getUUID("mission"))
                val state=when(mode) { "pause_load" -> MissionState.PAUSED;"cancel_load" -> MissionState.CANCELLED;else -> MissionState.REVIEW_REQUIRED }
                check(record.state==state) { "Unexpected restored state ${record.state}: ${record.detail}" }
                if(mode=="receipt_load" || mode=="pause_load") check(s.record.status==io.samcnpc.behavior.task.TaskStatus.PAUSED) { "Uncertain/paused operation still running" }
                check(record.stageId==expected.getString("stage"))
                if(ticks>=40) finish(event.server,"PASS_RESTART_${mode.uppercase()}_NO_REPLAY")
                return@guarded
            }
            val saveBoundary=(mode=="boundary_save" && record.stageId=="travel" || mode=="full_boundary_save" && record.stageId=="cobble") && record.state==MissionState.READY
            val saveReceipt=mode=="receipt_save" && record.stageId=="travel" && record.state==MissionState.RUNNING
            val savePhysical=mode=="physical_save" && record.stageId=="travel" && record.state==MissionState.RUNNING && s.record.status==io.samcnpc.behavior.task.TaskStatus.COMPLETED
            val saveControl=mode in setOf("pause_save","cancel_save") && record.stageId=="travel" && record.state==MissionState.RUNNING
            if(saveBoundary || saveReceipt || savePhysical || saveControl) {
                if(saveReceipt) { record.state=MissionState.READY;record.taskId=null;record.detail="fixture: assignment returned, mission binding not saved";MissionStore.forServer(event.server).setDirty() }
                if(saveControl) check(MissionApi.control(event.server,a.player,s.npcId,if(mode=="pause_save") MissionControl.PAUSE else MissionControl.CANCEL).status==NpcActionStatus.SUCCEEDED)
                expected.putLong("pid",ProcessHandle.current().pid());expected.putUUID("actor",a.player.uuid)
                expected.putUUID("mission",record.id);record.taskId?.let { expected.putUUID("task",it) }
                expected.put("scene",s.metadata());expected.putString("stage",record.stageId)
                NbtIo.writeCompressed(expected,checkpoint.toFile())
                finish(event.server,"PASS_BOUNDARY_CHECKPOINT",close=false);return@guarded
            }
            if(mode=="full_stock_loss" && record.stageId=="coal" && pausedAt==0) {
                val chest=s.chest(0,3)
                var removed=0
                for(slot in 0 until chest.containerSize) if(chest.getItem(slot).`is`(Items.OAK_LOG)) removed+=chest.removeItem(slot,64).count
                check(removed==32);chest.setChanged();pausedAt=ticks
                rows.add(mapOf("fixtureMutation" to "player removes 32 delivered oak logs", "tick" to ticks,"removed" to removed))
            }
            visited.add(record.stageId)
            if(ticks%20==0 || record.terminal || record.state==MissionState.REVIEW_REQUIRED) {
                val diagnostics=BehaviorRuntimeService.diagnostic(s.npcId)
                rows.add(mapOf("tick" to ticks,"mission" to record.id,"stage" to record.stageId,"state" to record.state,
                    "task" to record.taskId,"remaining" to record.remaining,"position" to s.npc.snapshot().position,
                    "selected" to diagnostics?.selectedIntents,"sourceIronAxes" to s.count(3,0,Items.IRON_AXE),
                    "carriedIronAxes" to s.carried("minecraft:iron_axe"),"detail" to record.detail,
                    "output" to if(full) listOf(s.count(0,3,Items.OAK_LOG),s.count(0,3,Items.COBBLESTONE),s.count(0,3,Items.COAL)) else null,
                    "taskStatus" to diagnostics?.taskStatus,"work" to diagnostics?.work))
                write("RUNNING")
            }
            if(mode=="load" || mode=="full_load") {
                check(record.id==expected.getUUID("mission")) { "mission identity changed across JVMs" }
                if(record.stageId==expected.getString("stage") && record.state==MissionState.RUNNING) check(record.taskId==expected.getUUID("task"))
            }
            if(mode=="pause" && record.stageId=="travel" && record.state==MissionState.RUNNING && pausedAt==0) {
                check(MissionApi.control(event.server,a.player,s.npcId,MissionControl.PAUSE).status==NpcActionStatus.SUCCEEDED)
                pausedAt=ticks;taskAtPause=record.taskId;budgetAtPause=record.remaining
            }
            if(mode=="pause" && pausedAt>0 && ticks<=pausedAt+40) {
                check(record.taskId==taskAtPause && record.remaining==budgetAtPause && record.state==MissionState.PAUSED)
                if(ticks==pausedAt+40) check(MissionApi.control(event.server,a.player,s.npcId,MissionControl.RESUME).status==NpcActionStatus.SUCCEEDED)
                return@guarded
            }
            if((mode=="cancel" || mode=="full_cancel") && record.stageId==(if(full) "wood" else "prepare") && record.state==MissionState.RUNNING && pausedAt==0) {
                taskAtPause=record.taskId;pausedAt=ticks
                check(MissionApi.control(event.server,a.player,s.npcId,MissionControl.CANCEL).status==NpcActionStatus.SUCCEEDED)
            }
            if((mode=="cancel" || mode=="full_cancel") && pausedAt>0) {
                check(record.state==MissionState.CANCELLED && record.stageId==(if(full) "wood" else "prepare") && record.taskId==taskAtPause)
                if(ticks>=pausedAt+40) finish(event.server,"PASS_CANCEL_NO_SUCCESSOR")
                return@guarded
            }
            if((mode=="save" && record.stageId=="travel" && s.body.x>1.2 || mode=="full_save" && record.stageId=="cobble" && s.record.primary.mining?.selection?.removed?.isNotEmpty()==true) && record.state==MissionState.RUNNING) {
                expected.putLong("pid",ProcessHandle.current().pid());expected.putUUID("actor",a.player.uuid)
                expected.putUUID("mission",record.id);expected.putUUID("task",checkNotNull(record.taskId));expected.put("scene",s.metadata())
                expected.putString("stage",record.stageId)
                NbtIo.writeCompressed(expected,checkpoint.toFile())
                finish(event.server,"PASS_ACTIVE_CHECKPOINT",close=false);return@guarded
            }
            if(mode=="full_no_coal" && record.state==MissionState.REVIEW_REQUIRED) {
                check(record.stageId=="coal" && s.count(0,3,Items.COAL)==0 && s.carried("minecraft:charcoal")==8)
                check("field" !in record.confirmed);finish(event.server,"PASS_IMPOSSIBLE_NO_COAL_SAFE_HOLD");return@guarded
            }
            if(mode=="full_stock_loss" && record.state==MissionState.REVIEW_REQUIRED) {
                check(record.stageId=="final_return" && record.detail.contains("final requirements") && s.count(0,3,Items.OAK_LOG)==0)
                finish(event.server,"PASS_FINAL_STOCK_NOT_LATCHED");return@guarded
            }
            check(record.state != MissionState.REVIEW_REQUIRED) { "mission held: ${record.detail}" }
            if(record.state==MissionState.COMPLETED) {
                if(full) {
                    verifyFull(s,record)
                    if(mode=="full_soak") {
                        rows.add(mapOf("roundCompleted" to round,"tick" to ticks,"mission" to record.id,"receipts" to record.confirmed.toMap(),"activeTicks" to activeTicks,"output" to listOf(32,30,2)))
                        if(activeTicks>=36000 && (System.nanoTime()-startedNanos)/1e9>=1800) {
                            finish(event.server,"PASS_ACTIVE_EXTERNAL_MISSION_SOAK");return@guarded
                        }
                        // Only after every physical assertion: remove this round's stock before rebuilding its isolated fixture.
                        for(x in -3..32) for(z in -8..12) for(y in 0..10) {
                            val container=s.level.getBlockEntity(s.pos(x,y,z)) as? net.minecraft.world.Container ?: continue
                            container.clearContent();container.setChanged()
                        }
                        s.close()
                        for(drop in s.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity::class.java,net.minecraft.world.phys.AABB(s.pos(-3,0,-8),s.pos(33,12,13)))) drop.discard()
                        scene=OperationScene.create(s.level,OperationKind.PREPARATION,BlockPos(0,64,0))
                        started=false;pausedAt=0;round++;roundStarted=ticks;write("RUNNING");return@guarded
                    }
                    finish(event.server,"PASS_FULL_PHYSICAL_MISSION");return@guarded
                }
                check(record.confirmed.keys==setOf("prepare","travel","return"))
                check(record.confirmed.values.distinct().size==3)
                s.requireReturned();check(s.npc.equipmentContents().mainHand.itemId=="minecraft:iron_axe")
                check(s.count(3,0,Items.IRON_AXE)==0 && s.carried("minecraft:iron_axe")==1)
                check(s.count(3,0,Items.STONE_AXE)==1 && s.count(3,0,Items.CHARCOAL)==11)
                check(BehaviorRuntimeService.assignedPacks(event.server,s.npcId).isEmpty())
                check(rows.any { row -> (row["selected"] as? List<*>)?.any { it.toString().startsWith("acceptance:") }==true }) { "External pack never won arbitration" }
                finish(event.server,"PASS_PHYSICAL_MISSION")
            }
        }
    }
    private fun prepareFull(s: OperationScene) {
        val tools=s.makeChest(3,-3);tools.setItem(0,ItemStack(Items.IRON_PICKAXE));tools.setItem(1,ItemStack(Items.IRON_HOE));tools.setItem(2,ItemStack(Items.STICK,17))
        val gear=s.makeChest(3,3);gear.setItem(0,ItemStack(Items.IRON_CHESTPLATE));gear.setItem(1,ItemStack(Items.DIRT,16))
        s.makeChest(0,3);s.give(Items.CHARCOAL,8)
        for(x in listOf(8,12,16,20)) for(z in listOf(-4,4)) {
            s.level.setBlock(s.pos(x,0,z),Blocks.DIRT.defaultBlockState(),3)
            for(y in 1..4) s.level.setBlock(s.pos(x,y,z),Blocks.OAK_LOG.defaultBlockState(),3)
            for(dx in -1..1) for(dz in -1..1) s.level.setBlock(s.pos(x+dx,5,z+dz),Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT,true),3)
        }
        for(x in 10..19) for(z in 8..10) s.level.setBlock(s.pos(x,1,z),Blocks.STONE.defaultBlockState(),3)
        if(mode!="full_no_coal") for(x in 24..25) s.level.setBlock(s.pos(x,1,8),Blocks.COAL_ORE.defaultBlockState(),3)
        for(x in -2..0) for(z in -4..-2) s.level.setBlock(s.pos(x,0,z),Blocks.DIRT.defaultBlockState(),3)
        s.level.setBlock(s.pos(1,0,-3),Blocks.WATER.defaultBlockState(),3)
        s.level.setBlock(s.pos(25,1,-4),Blocks.OAK_LOG.defaultBlockState(),3)
        s.level.setBlock(s.pos(26,1,-4),Blocks.OAK_PLANKS.defaultBlockState(),3)
    }
    private fun verifyFull(s: OperationScene,r: MissionRecord) {
        check(r.confirmed.size==10 && r.confirmed.values.distinct().size==10)
        s.requireReturned()
        check(s.count(0,3,Items.OAK_LOG)==32 && s.count(0,3,Items.COBBLESTONE)==30 && s.count(0,3,Items.COAL)==2)
        check(s.carried("minecraft:charcoal")==8 && s.count(3,0,Items.CHARCOAL)==11)
        check(s.npc.equipmentContents().chest.itemId=="minecraft:iron_chestplate")
        for(x in -2..0) for(z in -4..-2) check(s.level.getBlockState(s.pos(x,0,z)).`is`(Blocks.FARMLAND))
        check(s.level.getBlockState(s.pos(25,1,-4)).`is`(Blocks.OAK_LOG) && s.level.getBlockState(s.pos(26,1,-4)).`is`(Blocks.OAK_PLANKS))
        check(s.count(3,3,Items.DIRT)==16 && s.count(3,-3,Items.STICK)==17 && s.count(3,0,Items.STONE_AXE)==1)
        for(id in listOf("minecraft:iron_axe","minecraft:iron_pickaxe","minecraft:iron_hoe")) check(s.carried(id)==1)
        check(BehaviorRuntimeService.assignedPacks(s.server,s.npcId).isEmpty())
        check(rows.any { row -> (row["selected"] as? List<*>)?.any { it.toString().startsWith("acceptance:") }==true })
    }
    private fun finish(server: MinecraftServer,result: String,close: Boolean=true) {
        done=true;write(result)
        if(close) checkNotNull(scene).close()
        actor?.close();server.saveEverything(false,true,true);server.halt(false)
    }
    private fun write(result: String,error: String?=null) {
        Files.writeString(Path.of("external-mission-result.json"),gson.toJson(mapOf("result" to result,"mode" to mode,"seed" to 20260928,
            "ticks" to ticks,"activeTicks" to activeTicks,"round" to round,"wallSeconds" to (System.nanoTime()-startedNanos)/1e9,"pid" to ProcessHandle.current().pid(),"visited" to visited,"error" to error,"observations" to rows)))
        Files.writeString(Path.of("external-mission-result.txt"),"$result ${error.orEmpty()}\n")
    }
    private fun guarded(server: MinecraftServer,action:()->Unit) {
        try { action() } catch(error: Exception) {
            done=true;write("FAIL",error.stackTraceToString())
            com.mojang.logging.LogUtils.getLogger().error("External mission physical proof failed",error)
            actor?.close();server.halt(false)
        }
    }
}
