package io.samcnpc.behavior.operations

import java.util.UUID

/** Test-only evidence, independent of Minecraft's clock and global random source. */
internal data class ZooTaskSample(
    val taskId: UUID, val primaryId: UUID, val remainingTicks: Int, val frames: Int,
    val inBounds: Boolean, val liveControls: Int,
)

internal class ZooTaskOracle(initial: ZooTaskSample) {
    private val taskId = initial.taskId
    private val primaryId = initial.primaryId
    private var remaining = initial.remainingTicks
    var observations = 0
        private set

    fun observe(sample: ZooTaskSample, released: Boolean = false) {
        check(sample.taskId == taskId && sample.primaryId == primaryId) { "original intent identity changed" }
        check(sample.remainingTicks in 0..remaining) { "task budget increased or became negative" }
        check(sample.frames in 1..3) { "interruption stack escaped its bound" }
        check(sample.inBounds) { "body left the scenario's permitted travel region" }
        if (released) check(sample.liveControls == 0) { "terminal task retained Core control" }
        remaining = sample.remainingTicks
        observations++
    }
}

internal object ZooResourceOracle {
    fun conserve(before: Long, after: Long, externalDelta: Long = 0, produced: Long = 0, consumed: Long = 0) {
        check(before >= 0 && after >= 0 && produced >= 0 && consumed >= 0)
        val expected = Math.subtractExact(Math.addExact(Math.addExact(before, externalDelta), produced), consumed)
        check(after == expected) { "resource imbalance: before=$before external=$externalDelta produced=$produced consumed=$consumed after=$after expected=$expected" }
    }
}

internal data class ZooIncident(val id: String, val phase: String, val parameters: Map<String, String>)
internal data class ZooIncidentEvidence(val incident: ZooIncident, val tick: Int, val before: Map<String, String>, val after: Map<String, String>)

/** One ordered finite input trace. Incident callbacks live only in the native test scene. */
internal class ZooIncidents(val seed: Long, incidents: List<ZooIncident>) {
    private val inputs = incidents.map { it.copy(parameters = java.util.Map.copyOf(it.parameters)) }
    private val applied = mutableListOf<ZooIncidentEvidence>()
    val evidence: List<ZooIncidentEvidence> get() = applied.toList()
    val complete: Boolean get() = applied.size == inputs.size
    init {
        require(inputs.size in 1..32 && inputs.map { it.id }.distinct().size == inputs.size)
        require(inputs.all { it.id.isNotBlank() && it.phase.isNotBlank() })
    }
    fun pending(phase: String): ZooIncident? = inputs.getOrNull(applied.size)?.takeIf { it.phase == phase }
    fun record(incident: ZooIncident, tick: Int, before: Map<String, String>, after: Map<String, String>) {
        check(tick >= 0 && (applied.lastOrNull()?.tick ?: 0) <= tick)
        check(inputs.getOrNull(applied.size) == incident) { "incident repeated or applied out of order" }
        applied.add(ZooIncidentEvidence(incident, tick, java.util.Map.copyOf(before), java.util.Map.copyOf(after)))
    }
}
