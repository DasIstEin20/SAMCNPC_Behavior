package io.samcnpc.behavior.task

import io.samcnpc.core.api.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class UnresolvedWorkTest {
    private val uuid = UUID(0, 1)
    private val position = NpcBlockPosition(2, 64, 3)
    private val block = UnresolvedWorkBlock("minecraft:overworld", position, "minecraft:oak_log", "USER_CANCELLED")
    private fun emptyStore() = UnresolvedWorkStore.load(CompoundTag().apply { putInt("version", 1); put("npcs", ListTag()) })

    @Test fun repeatedReportsSurviveReloadWithoutReplacingEarlierPositionsOrInventingIdentity() {
        val store = emptyStore()
        assertEquals(NpcActionStatus.SUCCEEDED, store.record(uuid, listOf(block)).status)
        val unknown = block.copy(position = position.copy(x = 4), blockId = null)
        assertEquals(NpcActionStatus.SUCCEEDED, store.record(uuid, listOf(block, unknown)).status)
        val restored = UnresolvedWorkStore.load(store.save(CompoundTag()))
        assertEquals(listOf(block, unknown), restored.blocksFor(uuid))
        assertTrue(assertNotNull(restored.describe(uuid)).contains("unresolvedSupports=2"))
        assertEquals(NpcActionStatus.SUCCEEDED, restored.record(uuid, listOf(block)).status)
        assertEquals(2, restored.blocksFor(uuid).size)
    }

    @Test fun boundsRejectAtomicallyAndDoNotDiscardAnExistingObligation() {
        val store = emptyStore()
        val full = (0..63).map { block.copy(position = position.copy(x = it)) }
        assertEquals(NpcActionStatus.SUCCEEDED, store.record(uuid, full).status)
        assertEquals(NpcActionStatus.REJECTED, store.record(uuid, listOf(block.copy(position = position.copy(x = 64)))).status)
        assertEquals(full, store.blocksFor(uuid))
        for (id in 2L..128L) assertEquals(NpcActionStatus.SUCCEEDED, store.record(UUID(0, id), listOf(block)).status)
        assertEquals(NpcActionStatus.REJECTED, store.record(UUID(0, 129), listOf(block)).status)
        assertEquals(128, store.save(CompoundTag()).getList("npcs", 10).size)
    }

    @Test fun unknownMalformedAndDuplicatedReportsKeepTheirOriginalData() {
        val store = emptyStore(); store.record(uuid, listOf(block))
        val valid = store.save(CompoundTag())
        val variants = listOf(
            valid.copy().apply { putInt("version", 99) },
            valid.copy().apply { getList("npcs", 10).add(getList("npcs", 10).getCompound(0).copy()) },
            valid.copy().apply { getList("npcs", 10).getCompound(0).getList("blocks", 10).getCompound(0).putString("x", "2") },
            valid.copy().apply { getList("npcs", 10).getCompound(0).getList("blocks", 10).getCompound(0).putString("blockId", "invalid command") },
        )
        for (raw in variants) {
            val rejected = UnresolvedWorkStore.load(raw)
            assertNotNull(rejected.problem)
            assertEquals(NpcActionStatus.REJECTED, rejected.record(uuid, listOf(block)).status)
            assertEquals(raw, rejected.save(CompoundTag()))
        }
    }

    @Test fun onlyObservedAirInTheCorrectDimensionResolvesAReport() {
        val store = emptyStore(); store.record(uuid, listOf(block))
        val world = object : NpcWorldView {
            override var dimensionId = "minecraft:overworld"
            var observed: NpcBlockObservation? = null
            override fun observeBlock(position: NpcBlockPosition) = observed
            override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? = null
            override fun observeEntity(uuid: UUID): NpcEntityObservation? = null
            override fun queryEntities(query: NpcEntityQuery) = emptyList<NpcEntityObservation>()
            override fun raycast(request: NpcRaycastRequest) = NpcRaycastResult.Miss
        }
        store.reconcileAbsent(uuid, block.dimensionId, world)
        assertEquals(listOf(block), store.blocksFor(uuid))
        world.observed = NpcBlockObservation(position, "minecraft:dirt", false, true, false)
        store.reconcileAbsent(uuid, block.dimensionId, world)
        assertEquals(listOf(block), store.blocksFor(uuid))
        world.observed = NpcBlockObservation(position, "minecraft:air", true, false, false)
        world.dimensionId = "minecraft:the_nether"
        store.reconcileAbsent(uuid, block.dimensionId, world)
        assertEquals(listOf(block), store.blocksFor(uuid))
        world.dimensionId = block.dimensionId
        store.reconcileAbsent(uuid, block.dimensionId, world)
        assertTrue(store.blocksFor(uuid).isEmpty())
    }
}
