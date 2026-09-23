package io.samcnpc.behavior.task

import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class DescendingTunnelTest {
    private val origin = NpcBlockPosition(-15, 63, -21)
    private fun geometry(direction: TunnelDirection = TunnelDirection.EAST) = TunnelGeometry(origin, direction, 2, 3, 5, 1)
    private fun work(g: TunnelGeometry = geometry()) = MiningWorkOrder(WorkArea(g.bounds()), MiningMethod.TUNNEL,
        WorkResourceIds(listOf("minecraft:stone")), tunnel=g)
    private fun definition(g: TunnelGeometry = geometry()) = MiningTaskDefinition("minecraft:overworld", work(g),
        WorkResourceIds(listOf("minecraft:cobblestone")), ContainerChoices(listOf(NpcBlockPosition(-17,63,-23))),
        1, MiningCounting.CLEARED_VOLUME, NpcPosition(-17.5,63.0,-23.5))

    @Test fun descentCoversOnlyExactSlicesInEveryDirectionAndRetainsEachSupportingStep() {
        for (direction in TunnelDirection.entries) {
            val g=geometry(direction); val w=work(g)
            assertNull(w.validationProblem()); assertEquals(30,w.volume)
            assertEquals(70,w.area.bounds.width*w.area.bounds.height*w.area.bounds.depth)
            val cells=(0 until w.volume).map(w::cell)
            assertEquals(30,cells.toSet().size)
            for (step in 0..4) for (lateral in 0..1) {
                val x=origin.x+direction.x*step-direction.z*lateral
                val z=origin.z+direction.z*step+direction.x*lateral
                val floor=63-step
                assertEquals(listOf(floor+2,floor+1,floor), cells.drop((step*2+lateral)*3).take(3).map { it.y })
                for (y in 58..66) assertEquals(y in floor..floor+2, w.contains(NpcBlockPosition(x,y,z)))
                assertFalse(w.contains(NpcBlockPosition(x,floor-1,z)))
                assertEquals((floor..floor+2).map { NpcBlockPosition(x,it,z) }.toSet(),cells.filter { it.x==x && it.z==z }.toSet())
            }
        }
    }

    @Test fun invalidPublicDimensionsCoordinatesAndHeadroomFailBeforeAnyIndexArithmetic() {
        val base=geometry()
        val invalid=listOf(base.copy(stepDown=-1),base.copy(stepDown=2),base.copy(height=2),
            base.copy(width=Int.MAX_VALUE),base.copy(length=Int.MIN_VALUE),
            base.copy(origin=NpcBlockPosition(Int.MAX_VALUE,63,0)),
            base.copy(origin=NpcBlockPosition(0,Int.MIN_VALUE,0)),
            base.copy(origin=NpcBlockPosition(29_999_983,63,0)),
            base.copy(origin=NpcBlockPosition(0,-2047,0)))
        for (g in invalid) { assertNotNull(g.validationProblem()); assertFailsWith<IllegalArgumentException> { g.cell(0) } }
        assertNull(base.copy(height=2,stepDown=0).validationProblem())
    }

    @Test fun restartRejectsCeilingAndSupportFactsInsideBoxButOutsideAuthorizedTunnel() {
        val d=definition(); assertNull(d.validationProblem()); assertEquals(30,d.clearanceCells)
        val hole=NpcBlockPosition(origin.x,origin.y-1,origin.z)
        assertTrue(d.work.area.contains(hole)); assertFalse(d.work.contains(hole))
        assertFailsWith<IllegalArgumentException> { MiningSelectionState().confirmed(d.work,hole,"minecraft:stone") }
        val state=MiningTaskState(ProducedResources.initial(emptyMap()))
        for (field in listOf("cleared","removed","target")) {
            val tag=MiningStateCodec.write(state)
            val position=MiningOrderCodec.block(hole)
            when (field) {
                "cleared" -> tag.put(field,ListTag().apply { add(position) })
                "removed" -> tag.put(field,ListTag().apply { add(CompoundTag().apply { put("position",position);putString("id","minecraft:stone") }) })
                else -> { tag.putString("phase","WORK");tag.put(field,CompoundTag().apply { put("position",position);putString("id","minecraft:stone") }) }
            }
            assertFailsWith<IllegalArgumentException> { MiningStateCodec.read(tag,d) }
        }
    }

    @Test fun definitionVersionTwoPersistsDescentWhileVersionOneStaysStrictlyHorizontal() {
        val d=definition(); val record=TaskRecord.start(UUID(0,23),d,emptyList())
        record.primary.mining=MiningTaskState(ProducedResources.initial(emptyMap()))
        val decoded=TaskCodec.read(TaskCodec.write(record))
        val restored=assertIs<MiningTaskDefinition>(decoded.primary.definition)
        assertEquals(2,restored.version);assertEquals(1,restored.work.tunnel?.stepDown)
        assertEquals(d.work,restored.work)
        assertNotNull(d.copy(version=1).validationProblem())
        val legacy=definition(geometry().copy(stepDown=0)).copy(version=1)
        val saved=CompoundTag();MiningOrderCodec.writeDefinition(legacy,saved)
        assertFalse(saved.getCompound("work").getCompound("tunnel").contains("stepDown"))
        assertEquals(0,MiningOrderCodec.readDefinition(saved,legacy.dimensionId,legacy.budget,1).work.tunnel?.stepDown)
        saved.getCompound("work").getCompound("tunnel").putInt("stepDown",0)
        assertFailsWith<IllegalArgumentException> { MiningOrderCodec.readDefinition(saved,legacy.dimensionId,legacy.budget,1) }
    }
}
