package io.samcnpc.behavior.operations

import org.junit.jupiter.api.Test
import kotlin.test.*

class ZooHourClockTest {
    @Test fun onlyConsecutiveActivePacedIntervalsCount() {
        val clock=ZooHourClock()
        clock.observe(0,false);clock.observe(50_000_000,true);clock.observe(100_000_000,true)
        clock.observe(150_000_000,false);clock.observe(200_000_000,true);clock.observe(250_000_000,true)
        assertEquals(2,clock.activeTicks);assertEquals(0.1,clock.activeSeconds)
        clock.observe(10_250_000_000,true)
        assertEquals(1,clock.excludedLongGaps);assertEquals(2,clock.activeTicks)
        clock.observe(10_300_000_000,true);assertEquals(3,clock.activeTicks)
        assertFailsWith<IllegalArgumentException> { clock.observe(10_000_000_000,true) }
    }
    @Test fun acceleratedTicksCannotClaimAnHourAndProbeCannotBecomeFullEvidence() {
        val fast=ZooHourClock();fast.observe(0,true)
        for (tick in 1..72000) fast.observe(tick*1_000_000L,true)
        assertEquals(72000,fast.activeTicks);assertEquals(72.0,fast.activeSeconds);assertFalse(fast.reached(false))
        val probe=ZooHourClock();probe.observe(0,true)
        for (tick in 1..1200) probe.observe(tick*50_000_000L,true)
        assertTrue(probe.reached(true));assertFalse(probe.reached(false))
        val slow=ZooHourClock();slow.observe(0,true)
        for (tick in 1..36000) slow.observe(tick*100_000_000L,true)
        assertEquals(3600.0,slow.activeSeconds);assertFalse(slow.reached(false))
    }
    @Test fun aFullPacedHourMeetsBothIndependentConditions() {
        val clock=ZooHourClock();clock.observe(0,true)
        for (tick in 1..71999) clock.observe(tick*50_000_000L,true)
        assertFalse(clock.reached(false));clock.observe(3_600_000_000_000L,true);assertTrue(clock.reached(false))
    }
    @Test fun twoSixSlotPatternsCoverAllEightDistinctFamilies() {
        val a=ZooHourPlan.kinds(1);val b=ZooHourPlan.kinds(2)
        assertEquals(6,a.size);assertEquals(6,b.size);assertEquals(a,ZooHourPlan.kinds(3))
        assertEquals(ZooHourPlan.families,(a+b).map(ZooHourPlan::family).toSet())
        assertEquals(6,a.distinct().size);assertEquals(6,b.distinct().size)
    }
}
