package io.samcnpc.behavior.operations

import com.sun.management.ThreadMXBean
import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import java.lang.management.ManagementFactory
import kotlin.math.ceil

/** Fixed-size samples; only ordinary active ticks enter this mixed-workload measurement. */
internal class ZooHourMeasurements {
    private val allocations=ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val times=LongArray(108000)
    private val bytes=LongArray(108000)
    private var count=0
    private var started=0L
    private var allocated=0L
    private var eligible=false
    private var decisions=0L
    private var decisionNanos=0L
    var excludedTicks=0
        private set
    init {
        check(allocations.isThreadAllocatedMemorySupported)
        allocations.isThreadAllocatedMemoryEnabled=true
        check(java.lang.Boolean.getBoolean("samcnpc.behavior.profile"))
    }
    fun begin(measure: Boolean) {
        eligible=measure
        if (!measure) return
        allocated=allocations.getThreadAllocatedBytes(Thread.currentThread().id)
        started=System.nanoTime()
    }
    fun end(explicitFixtureMutation: Boolean) {
        if (!eligible) return
        eligible=false
        if (explicitFixtureMutation) { excludedTicks++;return }
        val elapsed=System.nanoTime()-started
        val used=allocations.getThreadAllocatedBytes(Thread.currentThread().id)-allocated
        check(elapsed >= 0 && used >= 0 && count < times.size)
        times[count]=elapsed;bytes[count]=used;count++
    }
    fun retire(scenes: List<ZooHourScene>) {
        for (s in scenes) {
            val work=checkNotNull(BehaviorRuntimeService.diagnostic(s.scene.npcId)).work
            check(work.timedDecisions > 0)
            decisions+=work.timedDecisions;decisionNanos+=work.totalDecisionNanos
        }
    }
    fun summary(): Map<String,Any> {
        val time=times.copyOf(count).sortedArray()
        val used=bytes.copyOf(count)
        return linkedMapOf("samples" to count,"excludedFixtureTicks" to excludedTicks,
            "tickP95Ms" to percentile(time,0.95)/1_000_000.0,"tickP99Ms" to percentile(time,0.99)/1_000_000.0,
            "tickMaxMs" to (time.lastOrNull() ?: 0)/1_000_000.0,"allocatedBytesPerTick" to if (count == 0) 0.0 else used.average(),
            "timedDecisions" to decisions,"decisionMeanMicros" to if (decisions == 0L) 0.0 else decisionNanos.toDouble()/decisions/1000.0,
            "gcCollections" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) },
            "gcMillis" to ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime.coerceAtLeast(0) })
    }
    fun verify() {
        check(count >= 600 && decisions > 0) { "insufficient ordinary mixed-workload samples" }
        val s=summary()
        check((s.getValue("tickP95Ms") as Double) <= 20.0) { "mixed-work p95 exceeds ADR 0070: $s" }
        check((s.getValue("tickP99Ms") as Double) <= 40.0) { "mixed-work p99 exceeds ADR 0070: $s" }
        check((s.getValue("allocatedBytesPerTick") as Double) <= 2.0*1024*1024) { "mixed-work allocation exceeds ADR 0070: $s" }
        check((s.getValue("decisionMeanMicros") as Double) <= 250.0) { "mixed-work decision cost exceeds ADR 0070: $s" }
    }
    fun raw()=linkedMapOf("tickWorkNanos" to times.copyOf(count),"allocatedBytes" to bytes.copyOf(count))
    private fun percentile(values: LongArray,portion: Double): Long = if (values.isEmpty()) 0
        else values[(ceil(values.size*portion).toInt()-1).coerceIn(values.indices)]
}
