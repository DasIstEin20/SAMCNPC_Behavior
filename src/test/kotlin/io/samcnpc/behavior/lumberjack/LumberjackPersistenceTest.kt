package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumberjackPersistenceTest {
    private val npcUuid = UUID.fromString("3060e844-139d-4807-a4d9-127ee9377993")

    @Test
    fun `scaffold waits have persisted bounded counters`() {
        val (root, entry) = fixture(16)
        val pillar = pillarFixture()
        pillar.putString("state", "DESCEND_LAND")
        pillar.putInt("positioningTicks", 21)
        pillar.putInt("cleanupTicks", 590)
        entry.put("pillarSession", pillar)
        val saved = LumberjackDemoStore.load(root).save(CompoundTag())
        val session = assertNotNull(assertNotNull(LumberjackDemoStore.load(saved).jobFor(npcUuid)).pillarSession)
        assertEquals(21, session.positioningTicks)
        assertEquals(590, session.cleanupTicks)
        pillar.putInt("cleanupTicks", Int.MAX_VALUE)
        assertEquals(601, assertNotNull(assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid)).pillarSession).cleanupTicks)
        pillar.remove("cleanupTicks")
        assertEquals(0, assertNotNull(assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid)).pillarSession).cleanupTicks)
    }

    @Test
    fun `nested access and material continuations remain distinct after reload`() {
        val (root, entry) = fixture(16)
        entry.putString("phase", "BREAK_LOG")
        entry.put("target", position(2, 1, 3))
        entry.put("blockedLog", position(3, 8, 3))
        entry.put("accessReturnTarget", position(3, 1, 3))
        entry.putBoolean("scaffoldMaterialRecovery", true)
        val saved = LumberjackDemoStore.load(root).save(CompoundTag())
        val job = assertNotNull(LumberjackDemoStore.load(saved).jobFor(npcUuid))
        assertEquals(NpcBlockPosition(2, 1, 3), job.targetPosition)
        assertEquals(NpcBlockPosition(3, 8, 3), job.blockedLogPosition)
        assertEquals(NpcBlockPosition(3, 1, 3), job.accessReturnTarget)
        assertTrue(job.scaffoldMaterialRecovery)
    }

    @Test
    fun `old accepted placement is retained for safe descent without a stale inventory comparison`() {
        val (root, entry) = fixture(15)
        entry.putString("phase", "PILLAR_UP")
        val pillar = pillarFixture()
        pillar.put("placement", position(3, 2, 3))
        pillar.putInt("expectedMaterialCount", 2)
        val placed = ListTag()
        placed.add(position(3, 1, 3))
        pillar.put("placedPositions", placed)
        entry.put("pillarSession", pillar)
        val job = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        val restored = assertNotNull(job.pillarSession)
        assertEquals(LumberjackDemoPhase.PILLAR_CLEANUP, job.phase)
        assertEquals(TemporaryPillarState.DESCEND_BREAK, restored.state)
        assertEquals(listOf(NpcBlockPosition(3, 1, 3), NpcBlockPosition(3, 2, 3)), restored.placedPositions)
        assertNull(restored.expectedMaterialCountAfterPlacement)
        assertNull(restored.currentPlacement)
    }

    @Test
    fun `collection barrier and bounded recovery survive serialization`() {
        val (root, entry) = fixture(16)
        entry.putString("phase", "COLLECT_TREE_DROPS")
        entry.putInt("pickupTicks", 88)
        entry.putInt("pickupQuietTicks", 17)
        entry.putInt("scaffoldRecoveryAttempts", 2)
        val store = LumberjackDemoStore.load(root)
        val saved = store.save(CompoundTag())
        assertEquals(16, saved.getInt("version"))
        val restored = assertNotNull(LumberjackDemoStore.load(saved).jobFor(npcUuid))
        assertEquals(LumberjackDemoPhase.COLLECT_TREE_DROPS, restored.phase)
        assertEquals(88, restored.pickupTicks)
        assertEquals(17, restored.pickupQuietTicks)
        assertEquals(2, restored.scaffoldRecoveryAttempts)
    }

    @Test
    fun `legacy columns and missing counters retain their established meaning`() {
        val (root, entry) = fixture(15)
        entry.putInt("scanCursor", 54)
        val job = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        assertEquals(54, job.scanCursor)
        assertEquals(0, job.pickupQuietTicks)
        assertEquals(0, job.scaffoldRecoveryAttempts)
        root.putInt("version", 4)
        assertEquals(2, assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid)).scanCursor)
        entry.putInt("pickupTicks", Int.MAX_VALUE)
        entry.putInt("pickupQuietTicks", -100)
        entry.putInt("scaffoldRecoveryAttempts", Int.MAX_VALUE)
        val bounded = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        assertEquals(LumberjackCollectionBudget.MAX_TICKS, bounded.pickupTicks)
        assertEquals(0, bounded.pickupQuietTicks)
        assertTrue(bounded.scaffoldRecoveryAttempts <= 3)
    }

    private fun fixture(version: Int): Pair<CompoundTag, CompoundTag> {
        val root = CompoundTag()
        root.putInt("version", version)
        val entry = CompoundTag()
        entry.putUUID("npcUuid", npcUuid)
        entry.putString("dimension", "minecraft:overworld")
        entry.putString("phase", "SEARCH_WOOD")
        entry.put("chest", position(0, 1, 0))
        entry.put("workCenter", position(1, 1, 1))
        val jobs = ListTag()
        jobs.add(entry)
        root.put("jobs", jobs)
        return root to entry
    }

    private fun pillarFixture(): CompoundTag {
        val pillar = CompoundTag()
        pillar.putUUID("taskId", UUID.fromString("26d9c0d7-de2b-4033-a662-925c5e613e25"))
        pillar.put("target", position(3, 8, 3))
        pillar.putString("state", "VERIFY_PLACEMENT")
        pillar.putInt("originalSlot", 0)
        pillar.putInt("sourceSlot", 2)
        pillar.putInt("activeSlot", 2)
        pillar.putString("materialItemId", "minecraft:oak_log")
        pillar.putInt("estimatedLevels", 3)
        return pillar
    }

    private fun position(x: Int, y: Int, z: Int): CompoundTag {
        val tag = CompoundTag()
        tag.putInt("x", x)
        tag.putInt("y", y)
        tag.putInt("z", z)
        return tag
    }
}
