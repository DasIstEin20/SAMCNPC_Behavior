package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.elevation.TemporaryPillarState
import io.samcnpc.behavior.lumberjack.model.LumberjackDemoPhase
import io.samcnpc.behavior.lumberjack.persistence.LumberjackDemoStore
import io.samcnpc.core.api.NpcBlockPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import io.samcnpc.core.api.NpcActionStatus
import kotlin.test.assertEquals
import io.samcnpc.behavior.lumberjack.model.LumberjackChestAccessStage
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
        assertEquals(19, saved.getInt("version"))
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

    @Test
    fun `chest access survives reload without overwriting suspended tree work`() {
        val (root, entry) = fixture(17)
        entry.putString("phase", "RETURN_TO_CHEST")
        entry.put("target", position(10, 8, 3))
        entry.putBoolean("resumeWorkAfterDeposit", true)
        entry.put("chestAccessTarget", position(2, 1, 0))
        entry.putInt("chestAccessTicks", 42)
        entry.putInt("chestAccessAttempts", 3)
        val store = LumberjackDemoStore.load(root)
        val active = assertNotNull(store.jobFor(npcUuid))
        active.chestApproach = NpcBlockPosition(1, 1, 0)
        val saved = store.save(CompoundTag())
        val job = assertNotNull(LumberjackDemoStore.load(saved).jobFor(npcUuid))
        assertEquals(NpcBlockPosition(10, 8, 3), job.targetPosition)
        assertTrue(job.resumeWorkAfterDeposit)
        assertEquals(NpcBlockPosition(2, 1, 0), job.chestAccessTarget)
        assertEquals(42, job.chestAccessTicks)
        assertEquals(3, job.chestAccessAttempts)
        assertNull(job.chestApproach)
        assertEquals(LumberjackChestAccessStage.CLEAR_FOLIAGE, job.chestAccessStage)
        assertEquals(0, job.chestAccessQuietTicks)
        entry.putInt("chestAccessTicks", Int.MAX_VALUE)
        entry.putInt("chestAccessAttempts", Int.MAX_VALUE)
        val bounded = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        assertEquals(LumberjackChestTravel.MAX_ACCESS_TICKS + 1, bounded.chestAccessTicks)
        assertEquals(LumberjackChestTravel.MAX_ACCESS_BLOCKS, bounded.chestAccessAttempts)
        root.putInt("version", 16)
        assertNull(assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid)).chestAccessTarget)
    }

    @Test
    fun `route wood collection resumes within its original budget and migrates legacy jobs`() {
        val (root, entry) = fixture(18)
        entry.putString("phase", "RETURN_TO_CHEST")
        entry.put("target", position(10, 8, 3))
        entry.put("chestAccessTarget", position(2, 2, 0))
        entry.putString("chestAccessStage", "COLLECT_WOOD")
        entry.putInt("chestAccessTicks", 185)
        entry.putInt("chestAccessQuietTicks", 12)
        val saved = LumberjackDemoStore.load(root).save(CompoundTag())
        val restored = assertNotNull(LumberjackDemoStore.load(saved).jobFor(npcUuid))
        assertEquals(LumberjackChestAccessStage.COLLECT_WOOD, restored.chestAccessStage)
        assertEquals(185, restored.chestAccessTicks)
        assertEquals(12, restored.chestAccessQuietTicks)
        assertEquals(NpcBlockPosition(10, 8, 3), restored.targetPosition)
        assertEquals(NpcBlockPosition(2, 2, 0), restored.chestAccessTarget)
        entry.putInt("chestAccessTicks", Int.MAX_VALUE)
        entry.putInt("chestAccessQuietTicks", Int.MAX_VALUE)
        val bounded = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        assertEquals(LumberjackCollectionBudget.MAX_TICKS, bounded.chestAccessTicks)
        assertEquals(LumberjackCollectionBudget.QUIET_TICKS, bounded.chestAccessQuietTicks)
        entry.putString("chestAccessStage", "CUT_WOOD")
        val cutting = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        assertEquals(LumberjackChestAccessStage.CUT_WOOD, cutting.chestAccessStage)
        assertEquals(LumberjackChestTravel.MAX_ACCESS_TICKS + 1, cutting.chestAccessTicks)
        assertEquals(0, cutting.chestAccessQuietTicks)
        root.putInt("version", 17)
        val legacy = assertNotNull(LumberjackDemoStore.load(root).jobFor(npcUuid))
        assertEquals(LumberjackChestAccessStage.CLEAR_FOLIAGE, legacy.chestAccessStage)
        assertEquals(0, legacy.chestAccessQuietTicks)
        assertEquals(NpcBlockPosition(2, 2, 0), legacy.chestAccessTarget)
    }

    @Test
    fun `future and oversized files stay unchanged and cannot accept another job`() {
        val valid = assertNotNull(LumberjackDemoStore.load(fixture(19).first).jobFor(npcUuid))
        val future = fixture(99).first.apply { putString("futureField", "retain me") }
        val oversized = fixture(19).first
        val entries = oversized.getList("jobs", 10)
        repeat(128) { entries.add(entries.getCompound(0).copy().apply { putUUID("npcUuid", UUID(0, it.toLong())) }) }
        for (root in listOf(future, oversized, fixture(19).first.apply { putString("version", "18") })) {
            val store = LumberjackDemoStore.load(root)
            assertNull(store.jobFor(npcUuid))
            assertNotNull(store.problemFor(npcUuid))
            assertEquals(NpcActionStatus.REJECTED, store.put(valid).status)
            assertEquals(root, store.save(CompoundTag()))
        }
    }

    @Test
    fun `duplicate jobs preserve both conflicting scaffold obligations`() {
        val (root, entry) = fixture(19)
        val duplicate = entry.copy().apply { put("pillarSession", pillarFixture().apply { putString("state", "DESCEND_BREAK") }) }
        root.getList("jobs", 10).add(duplicate)
        val store = LumberjackDemoStore.load(root)
        assertNull(store.jobFor(npcUuid))
        assertTrue(assertNotNull(store.problemFor(npcUuid)).contains("duplicate"))
        assertEquals(root, store.save(CompoundTag()))
    }

    @Test
    fun `bad dimensions positions list types and pillars are quarantined rather than dropped`() {
        val mutations: List<(CompoundTag) -> Unit> = listOf(
            { it.putString("dimension", "bad dimension") },
            { it.getCompound("chest").putString("x", "0") },
            { it.putString("previousPacks", "minecraft:idle") },
            { it.put("pillarSession", pillarFixture().apply { putString("state", "future state") }) },
            { it.put("pillarSession", pillarFixture()) },
            { it.putString("pickupTicks", "10") },
        )
        for (mutate in mutations) {
            val (root, entry) = fixture(19)
            mutate(entry)
            val store = LumberjackDemoStore.load(root)
            assertNull(store.jobFor(npcUuid))
            assertNotNull(store.problemFor(npcUuid))
            assertEquals(root, store.save(CompoundTag()))
        }
    }

    @Test
    fun `valid v19 neighbors keep running but malformed older files retain their source version`() {
        val (root, bad) = fixture(19)
        val goodId = UUID(0, 9)
        val good = bad.copy().apply { putUUID("npcUuid", goodId); putInt("scanCursor", 54) }
        bad.putString("phase", "unknown phase")
        root.getList("jobs", 10).add(good)
        val current = LumberjackDemoStore.load(root)
        assertNull(current.jobFor(npcUuid))
        assertEquals(54, assertNotNull(current.jobFor(goodId)).scanCursor)
        val saved = current.save(CompoundTag())
        assertTrue(saved.getList("jobs", 10).any { it == bad })
        root.putInt("version", 4)
        val legacy = LumberjackDemoStore.load(root)
        assertNull(legacy.jobFor(goodId))
        assertNotNull(legacy.problemFor(goodId))
        assertEquals(root, legacy.save(CompoundTag()))
    }

    @Test
    fun `all supported legacy versions preserve identity and established scan migration`() {
        for (version in 0..19) {
            val (root, entry) = fixture(version)
            entry.putInt("scanCursor", 54)
            val store = LumberjackDemoStore.load(root)
            assertNull(store.problemFor(npcUuid))
            val job = assertNotNull(store.jobFor(npcUuid))
            assertEquals(if (version < 5) 2 else 54, job.scanCursor)
            val restored = assertNotNull(LumberjackDemoStore.load(store.save(CompoundTag())).jobFor(npcUuid))
            assertEquals(job.npcUuid, restored.npcUuid)
            assertEquals(job.scanCursor, restored.scanCursor)
        }
    }

    @Test
    fun `assignment enforces the same 128 job limit as loading`() {
        val (root, entry) = fixture(19)
        val entries = root.getList("jobs", 10)
        entries.clear()
        repeat(128) { entries.add(entry.copy().apply { putUUID("npcUuid", UUID(0, it.toLong())) }) }
        val store = LumberjackDemoStore.load(root)
        val additional = assertNotNull(LumberjackDemoStore.load(fixture(19).first).jobFor(npcUuid))
        assertEquals(NpcActionStatus.REJECTED, store.put(additional).status)
        assertEquals(128, store.save(CompoundTag()).getList("jobs", 10).size)
        assertNull(store.jobFor(npcUuid))
    }

    @Test
    fun `confirmed support identities round trip and transient validation is rebuilt`() {
        val (root, entry) = fixture(18)
        val pillar = pillarFixture().apply {
            putString("state", "DESCEND_BREAK")
            put("placedPositions", ListTag().apply { add(position(3, 1, 3)) })
        }
        entry.put("pillarSession", pillar)
        val store = LumberjackDemoStore.load(root)
        val session = assertNotNull(assertNotNull(store.jobFor(npcUuid)).pillarSession)
        assertEquals("minecraft:oak_log", session.placedBlockIds[NpcBlockPosition(3, 1, 3)])
        session.revalidateSupports = false
        val saved = store.save(CompoundTag())
        assertEquals(19, saved.getInt("version"))
        val restored = assertNotNull(assertNotNull(LumberjackDemoStore.load(saved).jobFor(npcUuid)).pillarSession)
        assertEquals(session.placedBlockIds, restored.placedBlockIds)
        assertTrue(restored.revalidateSupports)
        assertTrue(!saved.toString().contains("revalidateSupports"))
    }

    @Test
    fun `arbitrary legacy item names do not manufacture block identity receipts`() {
        val (root, entry) = fixture(18)
        entry.put("pillarSession", pillarFixture().apply {
            putString("state", "DESCEND_BREAK"); putString("materialItemId", "example:unrelated_item_name")
            put("placedPositions", ListTag().apply { add(position(3, 1, 3)) })
        })
        val store = LumberjackDemoStore.load(root)
        val session = assertNotNull(assertNotNull(store.jobFor(npcUuid)).pillarSession)
        assertTrue(session.placedBlockIds.isEmpty())
        val restored = assertNotNull(assertNotNull(LumberjackDemoStore.load(store.save(CompoundTag())).jobFor(npcUuid)).pillarSession)
        assertEquals(session.placedPositions, restored.placedPositions)
        assertTrue(restored.placedBlockIds.isEmpty())
    }

    @Test
    fun `support identity cannot extend the recorded footprint`() {
        val (root, entry) = fixture(19)
        entry.put("pillarSession", pillarFixture().apply {
            putString("state", "DESCEND_BREAK")
            put("placedPositions", ListTag().apply { add(position(3, 1, 3)) })
            put("placedBlocks", ListTag().apply { add(position(5, 1, 3).apply { putString("blockId", "minecraft:dirt") }) })
        })
        val store = LumberjackDemoStore.load(root)
        assertNull(store.jobFor(npcUuid))
        assertNotNull(store.problemFor(npcUuid))
        assertEquals(root, store.save(CompoundTag()))
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
        pillar.put("placedBlocks", ListTag())
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
