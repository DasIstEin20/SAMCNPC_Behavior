package io.samcnpc.behavior.lumberjack

import io.samcnpc.behavior.kernel.elevation.*
import io.samcnpc.behavior.runtime.TestNpcFacade
import io.samcnpc.behavior.runtime.decisionContext
import io.samcnpc.core.api.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class SupportedWorkRegressionTest {
    @Test fun hangingCrownChoosesOnlyBoundedObservedSupportedFeetCells() {
        val world = Geometry()
        val trunk = NpcBlockPosition(0, 70, 0)
        val origin = NpcPosition(-2.5, 64.0, 0.5)
        val lower = NpcBlockPosition(-2, 64, 0)
        world.standing.add(lower)
        assertEquals(lower, selectMiningStance(origin, trunk, world))
        val normal = NpcBlockPosition(2, 70, 0)
        world.standing.add(normal)
        assertEquals(normal, selectMiningStance(origin, trunk, world))
        assertEquals(lower, selectMiningStance(origin, trunk, world, setOf(normal)))
        world.standing.clear()
        world.standing.add(NpcBlockPosition(-2, 61, 0))
        assertNull(selectMiningStance(origin, trunk, world))
        world.standing.add(lower)
        world.fluid = true
        assertNull(selectMiningStance(origin, trunk, world))
    }

    @Test fun scaffoldWaitsForARealLandingAndNeverPlacesWhileAirborne() {
        val body = Body()
        val world = Geometry()
        val session = session()
        assertIs<TemporaryPillarKernel.PillarProgress.Running>(TemporaryPillarKernel.tick(body, world, session))
        assertEquals(TemporaryPillarState.VALIDATE_BASE, session.state)
        assertEquals(1, session.positioningTicks)
        body.current = body.current.copy(onGround = true, position = NpcPosition(0.5, 64.0, 0.5))
        assertIs<TemporaryPillarKernel.PillarProgress.Running>(TemporaryPillarKernel.tick(body, world, session))
        assertEquals(TemporaryPillarState.POSITION_ON_SUPPORT, session.state)
        assertEquals(NpcBlockPosition(0, 64, 0), session.currentPlacement)
    }

    @Test fun landingDeadlineIsSpentAndFluidNeverReceivesALandingWait() {
        val body = Body()
        val world = Geometry()
        val session = session().copy(positioningTicks = 39)
        assertIs<TemporaryPillarKernel.PillarProgress.Running>(TemporaryPillarKernel.tick(body, world, session))
        assertEquals(40, session.positioningTicks)
        val failed = assertIs<TemporaryPillarKernel.PillarProgress.Failed>(TemporaryPillarKernel.tick(body, world, session))
        assertEquals(TemporaryPillarResultCode.PILLAR_UNSAFE_ENVIRONMENT, failed.code)
        assertEquals(41, session.positioningTicks)
        body.current = body.current.copy(inWater = true)
        val fluid = session()
        assertIs<TemporaryPillarKernel.PillarProgress.Failed>(TemporaryPillarKernel.tick(body, world, fluid))
        assertEquals(0, fluid.positioningTicks)
    }

    private fun session() = TemporaryPillarSession(UUID(0, 7), NpcBlockPosition(0, 71, 2),
        TemporaryPillarState.VALIDATE_BASE, 0, 1, 1, "minecraft:dirt", 2, 0,
        TemporaryPillarResultCode.PILLAR_STARTED, null, null, mutableListOf())

    private class Body : TestNpcFacade() {
        var current = decisionContext().snapshot.copy(onGround = false, position = NpcPosition(0.5, 64.05, 0.5))
        override fun snapshot() = current
    }

    private class Geometry : NpcWorldView {
        override val dimensionId = "minecraft:overworld"
        val standing = mutableSetOf<NpcBlockPosition>()
        var fluid = false
        override fun observeBlock(position: NpcBlockPosition): NpcBlockObservation {
            val id = if (position == NpcBlockPosition(0, 71, 2)) "minecraft:oak_log" else if (position.y == 63) "minecraft:dirt" else "minecraft:air"
            return NpcBlockObservation(position, id, id == "minecraft:air", id != "minecraft:air", false)
        }
        override fun observeStandingSpace(feet: NpcPosition) = NpcStandingSpaceObservation(feet, true,
            NpcBlockPosition(kotlin.math.floor(feet.x).toInt(), feet.y.toInt(), kotlin.math.floor(feet.z).toInt()) in standing, fluid)
        override fun observeBlockContainer(position: NpcBlockPosition): NpcBlockContainerObservation? = error("unexpected container read")
        override fun observeEntity(uuid: UUID): NpcEntityObservation? = error("unexpected entity read")
        override fun queryEntities(query: NpcEntityQuery): List<NpcEntityObservation> = error("unexpected entity scan")
        override fun raycast(request: NpcRaycastRequest): NpcRaycastResult = error("unexpected raycast")
    }
}
