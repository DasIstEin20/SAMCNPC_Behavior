package io.samcnpc.behavior.kernel.elevation
import io.samcnpc.core.api.NpcItemKnowledge
import io.samcnpc.core.api.NpcPillarMaterialClass
import io.samcnpc.core.api.NpcPlaceableBlockKnowledge
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporaryPillarKernelTest {
    @Test
    fun `ordinary wood remains a last-resort scaffold material`() {
        assertTrue(TemporaryPillarKernel.isPermittedMaterial(material(NpcPillarMaterialClass.AVOID)))
    }

    @Test
    fun `explicitly forbidden blocks cannot become scaffolding`() {
        assertFalse(TemporaryPillarKernel.isPermittedMaterial(material(NpcPillarMaterialClass.FORBIDDEN)))
    }

    private fun material(materialClass: NpcPillarMaterialClass): NpcItemKnowledge = NpcItemKnowledge(
        itemId = "minecraft:oak_log",
        roles = emptySet(),
        placeableBlock = NpcPlaceableBlockKnowledge(
            fullCollision = true,
            gravityAffected = false,
            hazardous = false,
            functional = false,
            hasBlockEntity = false,
            pillarMaterialClass = materialClass,
        ),
    )
}
