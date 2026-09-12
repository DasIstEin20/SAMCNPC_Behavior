package io.samcnpc.behavior.operations

import com.google.gson.GsonBuilder
import com.sun.management.ThreadMXBean
import io.samcnpc.behavior.SamcnpcBehavior
import io.samcnpc.behavior.runtime.*
import io.samcnpc.behavior.task.*
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.ceil

/** Tick work excludes the server's 50ms pacing wait and the fixture's between-window setup. */
@Mod.EventBusSubscriber(modid=SamcnpcBehavior.MOD_ID)
object OperationsPerformance {
    private val enabled=java.lang.Boolean.getBoolean("samcnpc.operationsPerformance")
    private val logger=com.mojang.logging.LogUtils.getLogger()
    private val windows=listOf(1,8,32,64).flatMap { listOf(it to false,it to true) }
    private val rows=mutableListOf<Map<String,Any>>()
    private var scenes=emptyList<OperationScene>()
    private var window=0
    private var tick=0
    private var ready=false
    private var done=false
    private var serverReady=false
    private var totalTicks=0
    private val times=LongArray(400)
    private val allocations=LongArray(400)
    private var samples=0
    private var tickStarted=0L
    private var allocatedStarted=0L
    private var bean: ThreadMXBean?=null
    private var before=emptyList<BehaviorWorkStatistics>()
    private var planningBefore=0L
    private var gcBefore=0L
    private var measureStarted=0L
    private val legs=mutableMapOf<UUID,Int>()
    private val legStart=mutableMapOf<UUID,Int>()
    private val legWall=mutableMapOf<UUID,Long>()
    private val completedNpcs=mutableSetOf<UUID>()
    private val completionTicks=mutableListOf<Int>()
    private val completionMillis=mutableListOf<Double>()

    @SubscribeEvent fun started(event: ServerStartedEvent) {
        if(!enabled) return
        guarded(event.server) {
            check(event.server.isDedicatedServer && event.server.playerCount == 0)
            check(java.lang.Boolean.getBoolean("samcnpc.behavior.profile")) { "Decision profiling must be enabled for this evidence" }
            bean=ManagementFactory.getThreadMXBean() as? ThreadMXBean
            val allocationBean=checkNotNull(bean) { "JVM does not expose server-thread allocation measurements" }
            check(allocationBean.isThreadAllocatedMemorySupported)
            allocationBean.isThreadAllocatedMemoryEnabled=true
            OperationCases.configure(event.server); setup(event.server); serverReady=true
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) fun begin(event: TickEvent.ServerTickEvent) {
        if(!enabled || done || !serverReady || event.phase != TickEvent.Phase.START) return
        allocatedStarted=checkNotNull(bean).getThreadAllocatedBytes(Thread.currentThread().id)
        tickStarted=System.nanoTime()
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) fun end(event: TickEvent.ServerTickEvent) {
        if(!enabled || done || !serverReady || event.phase != TickEvent.Phase.END) return
        val workNanos=System.nanoTime()-tickStarted
        val allocated=checkNotNull(bean).getThreadAllocatedBytes(Thread.currentThread().id)-allocatedStarted
        guarded(event.server) {
            totalTicks++
            check(totalTicks <= 8000) { "Performance campaign timed out" }
            if(!ready) {
                if(!scenes.all { it.loaded && it.body.onGround() && BehaviorRuntimeService.diagnostic(it.npcId) != null }) return@guarded
                ready=true
                if(windows[window].second) for(s in scenes) startLeg(s)
            }
            tick++
            if(tick in 201..600) {
                check(allocated >= 0)
                times[samples]=workNanos; allocations[samples]=allocated; samples++
            }
            if(windows[window].second) for(s in scenes) {
                val record=s.record
                check(record.status != TaskStatus.FAILED && record.status != TaskStatus.CANCELLED) { "Performance navigation failed: ${record.report()}" }
                if(record.status.terminal) {
                    s.requireCompleted()
                    val destination=if((legs[s.npcId] ?: 0)%2 == 0) s.point(26,0) else s.start
                    check(TaskNavigator.distanceSquared(s.npc.snapshot().position,destination) <= 0.75*0.75)
                    if(tick > 200) {
                        completedNpcs.add(s.npcId)
                        completionTicks.add(tick-checkNotNull(legStart[s.npcId]))
                        completionMillis.add((System.nanoTime()-checkNotNull(legWall[s.npcId]))/1_000_000.0)
                    }
                    legs[s.npcId]=(legs[s.npcId] ?: 0)+1; startLeg(s)
                }
            }
            if(tick == 200) {
                before=scenes.map { checkNotNull(BehaviorRuntimeService.diagnostic(it.npcId)).work }
                planningBefore=BehaviorPlanning.statistics().deferredSlices
                gcBefore=gcMillis(); measureStarted=System.nanoTime()
            }
            if(tick == 600) finishWindow(event.server)
        }
    }
    private fun setup(server: MinecraftServer) {
        val count=windows[window].first
        scenes=(0 until count).map { index -> OperationScene.create(server.overworld(),OperationKind.NAVIGATE,BlockPos(400+index%8*48,80,400+index/8*48)) }
        tick=0; samples=0; ready=false; legs.clear(); legStart.clear(); legWall.clear(); completionTicks.clear(); completionMillis.clear(); completedNpcs.clear()
        logger.info("O4_PERFORMANCE_SETUP count={} active={}",count,windows[window].second)
    }
    private fun startLeg(s: OperationScene) {
        val target=if((legs[s.npcId] ?: 0)%2 == 0) s.point(26,0) else s.start
        s.assign(NavigateTaskDefinition(s.npc.snapshot().dimensionId,target,budget=TaskBudget(1800)))
        legStart[s.npcId]=tick; legWall[s.npcId]=System.nanoTime()
    }
    private fun finishWindow(server: MinecraftServer) {
        check(samples == 400)
        val after=scenes.map { checkNotNull(BehaviorRuntimeService.diagnostic(it.npcId)).work }
        val active=windows[window].second
        val decisions=after.sumOf { it.decisions }-before.sumOf { it.decisions }
        check(decisions >= scenes.size*399L && after.all { it.timedDecisions > 0 })
        if(active) check(completedNpcs.containsAll(scenes.map { it.npcId })) { "Not every active NPC produced a real navigation result" }
        val state=CompoundTag(); val current=ListTag()
        if(active) for(s in scenes) current.add(TaskCodec.write(s.record))
        state.put("tasks",current)
        val observations=BehaviorObservationKind.entries.associate { kind -> kind.name to (after.sumOf { it.observations[kind] ?: 0L }-before.sumOf { it.observations[kind] ?: 0L }) }
        val row=linkedMapOf<String,Any>(
            "npcCount" to scenes.size,"mode" to if(active) "active_navigation" else "idle",
            "warmupTicks" to 200,"measuredTicks" to samples,"measuredWallSeconds" to (System.nanoTime()-measureStarted)/1_000_000_000.0,
            "tickMedianMs" to percentile(times,0.50)/1_000_000.0,"tickP95Ms" to percentile(times,0.95)/1_000_000.0,"tickP99Ms" to percentile(times,0.99)/1_000_000.0,
            "tickMaxMs" to times.max()/1_000_000.0,"allocatedBytesPerTick" to allocations.average(),"allocationP95Bytes" to percentile(allocations,0.95),
            "decisions" to decisions,"decisionMeanMicros" to (after.sumOf { it.totalDecisionNanos }-before.sumOf { it.totalDecisionNanos })/decisions/1000.0,
            "worldObservations" to observations,"deferredPlanningSlices" to BehaviorPlanning.statistics().deferredSlices-planningBefore,
            "serializedCurrentTaskBytes" to serializedSize(state),"serializedStoreWithRetainedReportsBytes" to serializedSize(TaskStore.forServer(server).save(CompoundTag())),
            "completedNpcs" to completedNpcs.size,"completedLegs" to completionTicks.size,"completionTicks" to completionTicks.toList(),"completionWallMillis" to completionMillis.toList(),
            "gcCollectionMillis" to gcMillis()-gcBefore)
        rows.add(row)
        writeResults()
        logger.info("O4_PERFORMANCE_RESULT {}",row)
        for(s in scenes) {
            if(active && !s.record.status.terminal) TaskService.cancel(server,s.npcId)
            s.close()
            check(BehaviorRuntimeService.diagnostic(s.npcId) == null) { "Removed performance NPC retained runtime state" }
        }
        window++
        if(window == windows.size) {
            done=true
            Files.writeString(Path.of("operations-performance.txt"),"PASS windows=8 counts=1,8,32,64 idle_and_active=true real_server_ticks=true allocations=true results=operations-performance.json\n")
            server.saveEverything(false,true,true); server.halt(false)
        } else setup(server)
    }
    private fun writeResults() {
        val report=linkedMapOf<String,Any>("kind" to "BASELINE","java" to System.getProperty("java.runtime.version"),
            "vmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments.filter { it.startsWith("-X") },"availableProcessors" to Runtime.getRuntime().availableProcessors(),
            "maxHeapBytes" to Runtime.getRuntime().maxMemory(),"rows" to rows)
        Files.writeString(Path.of("operations-performance.json"),GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n")
    }
    private fun serializedSize(tag: CompoundTag): Int {
        val bytes=ByteArrayOutputStream(); DataOutputStream(bytes).use { NbtIo.write(tag,it) }; return bytes.size()
    }
    private fun percentile(values: LongArray,p: Double): Long = values.sortedArray()[(ceil(values.size*p).toInt()-1).coerceIn(0,values.lastIndex)]
    private fun gcMillis()=ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) }
    private inline fun guarded(server: MinecraftServer,action: () -> Unit) {
        try { action() }
        catch(error: Exception) {
            done=true; logger.error("Operations performance failed",error)
            Files.writeString(Path.of("operations-performance.txt"),"FAIL window=$window tick=$tick\n${error.stackTraceToString()}\n")
            server.saveEverything(false,true,true); server.halt(false)
        }
    }
}
