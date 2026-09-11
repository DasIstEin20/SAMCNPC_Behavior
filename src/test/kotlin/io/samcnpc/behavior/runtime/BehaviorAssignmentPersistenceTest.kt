package io.samcnpc.behavior.runtime

import io.samcnpc.core.api.NpcActionStatus
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class BehaviorAssignmentPersistenceTest {
    private val npc = UUID(0, 1)

    @Test
    fun unversionedAssignmentsMigrateWithoutChangingOrder() {
        val ids = listOf("samcnpc:retaliate", "samcnpc:follow_summoner")
        val store = BehaviorAssignmentStore.load(root(entry(ids)))
        assertEquals(ids, store.packsFor(npc))
        val saved = store.save(CompoundTag())
        assertEquals(1, saved.getInt("version"))
        assertEquals(ids, BehaviorAssignmentStore.load(saved).packsFor(npc))
        assertNull(store.problemFor(npc))
    }

    @Test
    fun oversizedOrDuplicateNpcEntriesBecomeSafeIdleInsteadOfPartialAssignments() {
        val tooMany = root(entry((1..9).map { "test:pack$it" }))
        val duplicate = root(entry(listOf("test:first")), entry(listOf("test:second")))
        for (tag in listOf(tooMany, duplicate)) {
            val store = BehaviorAssignmentStore.load(tag)
            assertTrue(store.packsFor(npc).isEmpty())
            assertNotNull(store.problemFor(npc))
            assertNotNull(BehaviorAssignmentStore.load(store.save(CompoundTag())).problemFor(npc))
            assertEquals(NpcActionStatus.SUCCEEDED, store.replace(npc, listOf("test:fixed")).status)
            assertNull(store.problemFor(npc))
        }
    }

    @Test
    fun aFutureVersionCannotBeOverwrittenByAnOlderRuntime() {
        val tag = root(entry(listOf("test:future")))
        tag.putInt("version", 99)
        tag.putString("futureExtension", "preserve this")
        val store = BehaviorAssignmentStore.load(tag)
        assertTrue(store.packsFor(npc).isEmpty())
        assertNotNull(store.problemFor(npc))
        assertEquals(NpcActionStatus.REJECTED, store.replace(npc, listOf("test:replacement")).status)
        assertEquals(tag, store.save(CompoundTag()))
    }

    @Test
    fun replacingCopiesCallerDataAndRejectsInvalidIdsAtomically() {
        val store = BehaviorAssignmentStore.load(root())
        val caller = mutableListOf("test:good")
        assertEquals(NpcActionStatus.SUCCEEDED, store.replace(npc, caller).status)
        caller.clear()
        assertEquals(listOf("test:good"), store.packsFor(npc))
        assertEquals(NpcActionStatus.REJECTED, store.replace(npc, listOf("bad id")).status)
        assertEquals(listOf("test:good"), store.packsFor(npc))
    }

    @Test
    fun repairingARejectedEntryDoesNotRequireAnExtraSlotInFullStorage() {
        val root = root()
        val entries = root.getList("assignments", 10)
        repeat(4096) { index ->
            entries.add(entry(if (index == 1) listOf("invalid pack") else listOf("test:valid")).apply {
                putUUID("npcUuid", UUID(0, index.toLong()))
            })
        }
        val store = BehaviorAssignmentStore.load(root)
        assertNotNull(store.problemFor(npc))
        assertEquals(NpcActionStatus.REJECTED, store.replace(UUID(1, 1), listOf("test:new")).status)
        assertEquals(NpcActionStatus.SUCCEEDED, store.replace(npc, listOf("test:fixed")).status)
        assertNull(store.problemFor(npc))
        assertEquals(4096, store.save(CompoundTag()).getList("assignments", 10).size)
        assertEquals(listOf("test:fixed"), store.packsFor(npc))
    }

    private fun root(vararg entries: CompoundTag) = CompoundTag().apply {
        put("assignments", ListTag().apply { entries.forEach(::add) })
    }
    private fun entry(ids: List<String>) = CompoundTag().apply {
        putUUID("npcUuid", npc)
        put("packs", ListTag().apply { ids.forEach { add(StringTag.valueOf(it)) } })
    }
}
