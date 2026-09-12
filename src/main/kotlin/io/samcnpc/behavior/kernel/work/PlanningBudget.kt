package io.samcnpc.behavior.kernel.work

import java.util.UUID

internal enum class PlanningKind { GENERAL, FOREST, RECONCILIATION, CONTAINER, PICKUP, RETREAT, COMBAT_SEARCH, REACTION_SEARCH, YIELDING }

/** Reserves complete bounded planning slices before queries; active world effects never stop halfway. */
internal class PlanningBudget(
    val unitsPerTick: Int = 16384,
    private val maxSlice: Int = 8192,
    private val maxWaiters: Int = 4096,
) {
    private data class Key(val npc: UUID, val kind: PlanningKind)
    private data class Request(val key: Key, val firstTick: Long, var lastTick: Long, var units: Int)
    private data class Grant(val tick: Long, var remaining: Int)
    private val requests = mutableMapOf<Key, Request>()
    private val grants = mutableMapOf<Key, Grant>()
    private var tick: Long? = null
    private var reserved = 0
    private var consumed = 0
    private var lastConsumed = 0
    private var lastReserved = 0
    private var peakReserved = 0
    private var deferred = 0L
    private var admitted = 0L
    private var observed = 0L
    private var previousObserved = 0L
    private var peakObserved = 0L
    init { require(unitsPerTick in 1..1_000_000 && maxSlice in 1..unitsPerTick && maxWaiters in 1..4096) }

    fun acquire(npc: UUID, now: Long, units: Int, kind: PlanningKind = PlanningKind.GENERAL): Boolean {
        require(now >= 0 && units in 1..maxSlice) { "planning slice exceeds its declared bounds" }
        advance(now)
        val key = Key(npc, kind)
        val grant = grants[key]
        if (grant != null && grant.tick == now && grant.remaining >= units) {
            grant.remaining -= units; consumed += units; admitted++
            request(key, now, units)
            return true
        }
        request(key, now, units)
        deferred++
        return false
    }
    fun advance(now: Long) {
        if (tick != null && now < checkNotNull(tick)) clear()
        if (tick == now) return
        lastConsumed = consumed; lastReserved = reserved; previousObserved = observed; peakObserved = maxOf(peakObserved, observed); observed = 0
        tick = now; reserved = 0; consumed = 0; grants.clear()
        requests.entries.removeIf { now - it.value.lastTick > 40 }
        val ordered = requests.values.filter { it.firstTick < now }.sortedWith(compareBy<Request> { it.firstTick }.thenBy { it.key.npc }.thenBy { it.key.kind.ordinal })
        for (entry in ordered) {
            if (reserved + entry.units > unitsPerTick) continue
            grants[entry.key] = Grant(now, entry.units)
            reserved += entry.units
            requests.remove(entry.key)
        }
        peakReserved = maxOf(peakReserved, reserved)
    }
    private fun request(key: Key, now: Long, units: Int) {
        val old = requests[key]
        if (old != null) { old.lastTick = now; old.units = maxOf(old.units, units); return }
        if (requests.size + grants.size >= maxWaiters && key !in grants) return
        requests[key] = Request(key, now, now, units)
    }
    fun observedQuery() { observed++ }
    fun release(npc: UUID) { requests.keys.removeIf { it.npc == npc }; grants.keys.removeIf { it.npc == npc } }
    fun clear() { requests.clear(); grants.clear(); tick = null; reserved = 0; consumed = 0; lastConsumed = 0; lastReserved = 0; peakReserved = 0; deferred = 0; admitted = 0; observed = 0; previousObserved = 0; peakObserved = 0 }
    fun statistics() = Statistics(unitsPerTick, reserved, consumed, lastReserved, lastConsumed, peakReserved, requests.size, admitted, deferred, observed, previousObserved, maxOf(peakObserved, observed))
    data class Statistics(val limit: Int, val reserved: Int, val consumed: Int, val previousReserved: Int, val previousConsumed: Int,
                          val peakReserved: Int, val waiting: Int, val admittedSlices: Long, val deferredSlices: Long, val observedQueries: Long, val previousObservedQueries: Long, val peakObservedQueries: Long)
}
