package io.samcnpc.behavior.gametest

import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraftforge.gametest.GameTestHolder
import net.minecraftforge.gametest.PrefixGameTestTemplate

/** Independent required cases, with fresh bodies, task identities, loot physics and world cells. */
@GameTestHolder("samcnpc_shared_site_zoo")
@PrefixGameTestTemplate(false)
object SharedMiningRegressionGameTests {
    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_1")
    fun freshPair1RetainsBothPhysicalQuotas(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_2")
    fun freshPair2RetainsBothPhysicalQuotas(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_3")
    fun freshPair3RetainsBothPhysicalQuotas(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_4")
    fun freshPair4RetainsBothPhysicalQuotas(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_5")
    fun freshPair5RetainsBothPhysicalQuotas(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_6")
    fun freshPair6RetainsBothPhysicalQuotas(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_7")
    fun collectionSurvives20TickNavigationBackoff(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper, 20)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_8")
    fun collectionSurvives200TickNavigationBackoff(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper, 200)

    @JvmStatic @GameTest(template = "lumberjackdemogametests.empty", timeoutTicks = 2600, batch = "shared_mining_9")
    fun collectionMovesAroundACloseStationaryWaitingNpc(helper: GameTestHelper) = SharedSiteGameTests.sharedMiners(helper, closeObstruction = true)


}
