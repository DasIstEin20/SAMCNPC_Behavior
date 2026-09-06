package io.samcnpc.behavior.lumberjack

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LumberjackCollectionBudgetTest {
    @Test
    fun `falling drops prevent premature completion`() {
        val step = LumberjackCollectionBudget.advance(30, 19, hasDrops = true, onGround = true)
        assertFalse(step.complete)
        assertEquals(0, step.quiet)
    }

    @Test
    fun `landing restarts the quiet interval`() {
        assertEquals(0, LumberjackCollectionBudget.advance(30, 19, hasDrops = false, onGround = false).quiet)
        val step = LumberjackCollectionBudget.advance(50, 19, hasDrops = false, onGround = true)
        assertTrue(step.complete)
        assertFalse(step.timedOut)
    }

    @Test
    fun `a stream of pickups cannot extend the finite task window`() {
        var elapsed = 0
        var quiet = 0
        repeat(LumberjackCollectionBudget.MAX_TICKS - 1) {
            val step = LumberjackCollectionBudget.advance(elapsed, quiet, hasDrops = true, onGround = true)
            assertFalse(step.complete)
            elapsed = step.elapsed
            quiet = step.quiet
        }
        val last = LumberjackCollectionBudget.advance(elapsed, quiet, hasDrops = true, onGround = true)
        assertTrue(last.complete)
        assertTrue(last.timedOut)
        assertEquals(LumberjackCollectionBudget.MAX_TICKS, last.elapsed)
    }
}
