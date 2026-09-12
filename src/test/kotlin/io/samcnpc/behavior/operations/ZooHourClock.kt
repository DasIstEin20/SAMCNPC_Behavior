package io.samcnpc.behavior.operations

/** Conservative active-work time: setup, idle/pause intervals and long scheduling gaps never count. */
internal class ZooHourClock {
    private var lastNanos: Long?=null
    private var wasActive=false
    var activeTicks=0
        private set
    var activeNanos=0L
        private set
    var excludedLongGaps=0
        private set
    val activeSeconds get() = activeNanos/1_000_000_000.0
    fun observe(now: Long,active: Boolean) {
        val previous=lastNanos
        require(previous == null || now >= previous) { "monotonic campaign clock moved backwards" }
        if (previous != null) {
            val elapsed=now-previous
            if (elapsed > 1_000_000_000L) excludedLongGaps++
            else if (elapsed > 0 && wasActive && active) { activeNanos+=elapsed;activeTicks++ }
        }
        lastNanos=now;wasActive=active
    }
    fun reached(probe: Boolean): Boolean = activeTicks >= (if (probe) 1200 else 72000) && activeSeconds >= (if (probe) 60.0 else 3600.0)
}

internal object ZooHourPlan {
    val families=setOf("wood","mining","farming","courier","fishing","combat","exploration","machine")
    fun kinds(round: Int): List<OperationKind> {
        require(round >= 1)
        val rotating=if (round%2 == 1) listOf(OperationKind.FISHING_WAIT,OperationKind.ATTACK)
            else listOf(OperationKind.EXPLORER_LEG,OperationKind.MACHINE)
        return listOf(OperationKind.WOOD_REPLANT,OperationKind.MINING,OperationKind.FARM,OperationKind.TRANSPORT)+rotating
    }
    fun family(kind: OperationKind): String = when (kind) {
        OperationKind.WOOD_REPLANT -> "wood"
        OperationKind.MINING -> "mining"
        OperationKind.FARM -> "farming"
        OperationKind.TRANSPORT -> "courier"
        OperationKind.FISHING_WAIT -> "fishing"
        OperationKind.ATTACK -> "combat"
        OperationKind.EXPLORER_LEG -> "exploration"
        OperationKind.MACHINE -> "machine"
        else -> error("${kind.name} is not an hourly Zoo role")
    }
}
