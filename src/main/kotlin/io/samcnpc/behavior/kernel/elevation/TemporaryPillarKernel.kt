package io.samcnpc.behavior.kernel.elevation

import io.samcnpc.core.api.NpcActionResult
import io.samcnpc.core.api.NpcActionStatus
import io.samcnpc.core.api.NpcBlockFace
import io.samcnpc.core.api.NpcBlockPlacement
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcControlInput
import io.samcnpc.core.api.NpcEntityQuery
import io.samcnpc.core.api.NpcFacade
import io.samcnpc.core.api.NpcNavigationRequest
import io.samcnpc.core.api.NpcPillarMaterialClass
import io.samcnpc.core.api.NpcPosition
import io.samcnpc.core.api.NpcRaycastRequest
import io.samcnpc.core.api.NpcRaycastResult
import io.samcnpc.core.api.NpcVector
import io.samcnpc.core.api.NpcWorldView
import io.samcnpc.behavior.kernel.navigation.isLeafOrSupportedSnowObstacle
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

private typealias PillarSession = TemporaryPillarSession
private typealias PillarState = TemporaryPillarState
private typealias PillarResultCode = TemporaryPillarResultCode

/**
 * Behavior-owned, one-level-at-a-time scaffold executor. It deliberately contains no Minecraft
 * world mutation: Core receives only normal jump, slot, look, break and BlockItem-use requests.
 * The caller supplies the work target; this controller never chooses a tree or a task.
 */
internal object TemporaryPillarKernel {
    internal const val MAX_CLEANUP_TICKS = 600
    private const val MAX_HEIGHT = 8
    private const val MAX_RETRIES = 2
    private const val BREAK_REACH = 4.5
    private const val SUPPORT_CENTER_TOLERANCE = 0.36
    private const val LEGAL_PLACEMENT_BOTTOM_OFFSET = 1.02
    private const val LANDING_BOTTOM_OFFSET = 0.90
    private const val MAX_BUILD_Y = 319
    private const val MIN_BUILD_Y = -63

    fun begin(npc: NpcFacade, world: NpcWorldView, target: NpcBlockPosition): PillarBeginResult {
        val snapshot = npc.snapshot()
        val feet = currentFeetCell(snapshot.position)
        var base = feet
        var baseValidation = validateBase(snapshot.position, snapshot.onGround, snapshot.inWater, snapshot.inLava, snapshot.climbing, snapshot.riding, base, world)
        if (baseValidation == PillarBeginResult.Failed(PillarResultCode.PILLAR_NO_SAFE_BASE)) {
            // A grounded body can overlap the edge of a block while its mathematical feet
            // cell is over air. Re-center on adjacent real support; do not demand a remote path
            // or pretend that the unsupported cell is a legal placement base.
            base = adjacentSupport(snapshot.position, world) ?: return baseValidation
            baseValidation = validateBase(snapshot.position, snapshot.onGround, snapshot.inWater, snapshot.inLava, snapshot.climbing, snapshot.riding, base, world)
        }
        if (baseValidation != null) {
            return baseValidation
        }
        val headroom = headroomBlock(base, world)
        if (headroom != null) {
            return headroom
        }
        val estimatedLevels = estimateLevels(snapshot.position, target)
        if (estimatedLevels !in 1..MAX_HEIGHT) {
            return PillarBeginResult.Failed(if (estimatedLevels > MAX_HEIGHT) PillarResultCode.PILLAR_HEIGHT_LIMIT else PillarResultCode.PILLAR_NOT_NEEDED)
        }
        if (base.y + estimatedLevels > MAX_BUILD_Y || base.y < MIN_BUILD_Y) {
            return PillarBeginResult.Failed(PillarResultCode.PILLAR_WORLD_LIMIT)
        }
        val materials = permittedMaterials(npc)
        if (materials.isEmpty()) {
            return PillarBeginResult.Failed(PillarResultCode.PILLAR_NO_MATERIAL)
        }
        // A session deliberately holds one material identity. Requiring that one stack to cover
        // the bounded estimate keeps placement accounting and restoration explicit; it never
        // silently swaps to a different block halfway up a live scaffold.
        val material = materials.firstOrNull { candidate -> candidate.count >= estimatedLevels }
        if (material == null) {
            return PillarBeginResult.Failed(PillarResultCode.PILLAR_INSUFFICIENT_MATERIAL)
        }
        return PillarBeginResult.Started(
            TemporaryPillarSession(
                taskId = UUID.randomUUID(),
                targetPosition = target,
                state = if (base == feet) TemporaryPillarState.VALIDATE_BASE else TemporaryPillarState.POSITION_ON_SUPPORT,
                originalSelectedHotbarSlot = snapshot.selectedHotbarSlot,
                materialOriginalSlot = material.slot,
                materialActiveSlot = if (material.slot < HOTBAR_SIZE) material.slot else snapshot.selectedHotbarSlot,
                materialItemId = material.itemId,
                estimatedLevels = estimatedLevels,
                retries = 0,
                lastResult = TemporaryPillarResultCode.PILLAR_STARTED,
                currentPlacement = if (base == feet) null else base,
                expectedMaterialCountAfterPlacement = null,
                placedPositions = mutableListOf(),
            ),
        )
    }

    fun tick(npc: NpcFacade, world: NpcWorldView, session: PillarSession): PillarProgress {
        val resuming = session.revalidateSupports
        if (resuming || session.state == PillarState.VALIDATE_BASE) {
            if (!reconcileSupports(npc, world, session)) return PillarProgress.Failed(session.lastResult, "recorded scaffold geometry changed or is unverified")
        }
        val snapshot = npc.snapshot()
        if (resuming && session.state != PillarState.DESCEND_BREAK && session.state != PillarState.DESCEND_LAND) {
            val top = session.placedPositions.maxByOrNull { it.y }
            if (top != null && (!snapshot.onGround || currentFeetCell(snapshot.position) != top.copy(y = top.y + 1))) {
                // An interruption can move the body while leaving honest placed blocks behind.
                // Resume from verified footing before reusing a pending jump/placement state.
                session.currentPlacement = top
                session.expectedMaterialCountAfterPlacement = null
                session.state = PillarState.WAIT_FOR_LANDING
            }
        }
        val targetObservation = world.observeBlock(session.targetPosition)
        if (targetObservation == null || targetObservation.isAir) {
            session.lastResult = PillarResultCode.PILLAR_TARGET_GONE
            return PillarProgress.TargetGone
        }
        if (snapshot.onGround && session.state == PillarState.VALIDATE_BASE) {
            workReachProgress(npc, world, session)?.let { return it }
        }
        return when (session.state) {
            PillarState.VALIDATE_BASE -> validateStepBase(snapshot.position, snapshot.onGround, snapshot.inWater, snapshot.inLava, snapshot.climbing, snapshot.riding, session, world)
            PillarState.POSITION_ON_SUPPORT -> positionOnSupport(npc, snapshot.position, session, world)
            PillarState.EQUIP_BLOCK -> equipMaterial(npc, session)
            PillarState.START_JUMP -> startJump(npc, snapshot.position, session, world)
            PillarState.WAIT_FOR_LEGAL_PLACEMENT_WINDOW -> placeDuringJump(npc, snapshot.position, snapshot.velocity.y, session, world)
            PillarState.VERIFY_PLACEMENT -> verifyPlacement(npc, session, world)
            PillarState.WAIT_FOR_LANDING -> waitForLanding(npc, snapshot.position, snapshot.onGround, session, world)
            PillarState.RESTORE_TASK_ITEM -> restoreTaskItem(npc, session)
            PillarState.DESCEND_BREAK, PillarState.DESCEND_LAND -> PillarProgress.Failed(PillarResultCode.PILLAR_RECOVERY_REQUIRED)
        }
    }

    /** The parent task calls this only after no useful elevated target remains. */
    fun beginCleanup(session: PillarSession) {
        session.cleanupTicks = 0
        session.state = PillarState.DESCEND_BREAK
        session.currentPlacement = null
        session.expectedMaterialCountAfterPlacement = null
        session.lastResult = PillarResultCode.PILLAR_CLEANUP_INCOMPLETE
    }

    /** Reuses the already verified vertical work scaffold for the parent task's next high target. */
    fun continueToTarget(session: PillarSession, target: NpcBlockPosition) {
        session.targetPosition = target
        session.state = PillarState.VALIDATE_BASE
        session.currentPlacement = null
        session.expectedMaterialCountAfterPlacement = null
        session.lastResult = PillarResultCode.PILLAR_STARTED
    }

    fun isPermittedMaterial(knowledge: io.samcnpc.core.api.NpcItemKnowledge): Boolean {
        val profile = knowledge.placeableBlock ?: return false
        return profile.fullCollision && !profile.gravityAffected && !profile.hazardous &&
            !profile.functional && !profile.hasBlockEntity &&
            profile.pillarMaterialClass in setOf(
                NpcPillarMaterialClass.PREFERRED,
                NpcPillarMaterialClass.ALLOWED,
                // Wood is deliberately lower ranked by the data tag, but remains a legitimate
                // last-resort temporary scaffold when a supplied chest lacks dirt or stone.
                NpcPillarMaterialClass.AVOID,
            )
    }

    /** Cheap terrain blocks are the normal scaffold choice; `AVOID` is an earned-log fallback. */
    fun isPrimaryMaterial(knowledge: io.samcnpc.core.api.NpcItemKnowledge): Boolean {
        if (!isPermittedMaterial(knowledge)) {
            return false
        }
        return knowledge.placeableBlock?.pillarMaterialClass in setOf(
            NpcPillarMaterialClass.PREFERRED,
            NpcPillarMaterialClass.ALLOWED,
        )
    }

    /**
     * Conservative one-block descent. Only positions recorded after actual placement may be
     * broken, and an occupied scaffold is left alone. The NPC uses its normal block-break API.
     */
    fun tickCleanup(npc: NpcFacade, world: NpcWorldView, session: PillarSession): PillarProgress {
        val snapshot = npc.snapshot()
        // Fluids can prevent a landing forever. Occupants and lost footing also have a
        // persisted deadline; neither a restart nor a transient pickup may renew it.
        if (snapshot.inWater || snapshot.inLava || snapshot.climbing || snapshot.riding) {
            npc.abortBlockBreak()
            session.lastResult = PillarResultCode.PILLAR_UNSAFE_ENVIRONMENT
            return PillarProgress.CleanupIncomplete
        }
        if (session.revalidateSupports && !reconcileSupports(npc, world, session)) return PillarProgress.CleanupIncomplete
        if (++session.cleanupTicks > MAX_CLEANUP_TICKS) {
            npc.abortBlockBreak()
            session.lastResult = PillarResultCode.PILLAR_CLEANUP_INCOMPLETE
            return PillarProgress.CleanupIncomplete
        }
        if (session.state == PillarState.DESCEND_LAND) {
            val removedSupport = session.currentPlacement ?: return PillarProgress.CleanupIncomplete
            if (!snapshot.onGround) {
                return PillarProgress.Running("waiting for normal physics to land one level below the removed scaffold block")
            }
            val feet = currentFeetCell(snapshot.position)
            val ground = world.observeBlock(NpcBlockPosition(feet.x, feet.y - 1, feet.z))
            if (feet.y > removedSupport.y || ground?.isSolid != true) {
                return PillarProgress.Running("waiting until the NPC is actually supported after removing one scaffold block")
            }
            // This exact position was recorded only after a successful placement verification.
            // Its observed disappearance and an actual landing complete one honest descent step.
            session.placedPositions.remove(removedSupport)
            session.placedBlockIds.remove(removedSupport)
            session.currentPlacement = null
            session.state = PillarState.DESCEND_BREAK
            return PillarProgress.Running("landed safely after removing one temporary scaffold block")
        }
        val pendingRemoval = session.currentPlacement
        if (session.state == PillarState.DESCEND_BREAK && pendingRemoval != null) {
            val observed = world.observeBlock(pendingRemoval)
            if (observed?.isAir != true && !matchesSupport(world, session, pendingRemoval)) {
                abortRelatedBreak(npc, session)
                return PillarProgress.CleanupIncomplete
            }
            val active = snapshot.blockBreak
            if (active != null && active.position == pendingRemoval) {
                return npc.continueBlockBreak().toProgress("removing one temporary scaffold block")
            }
            if (world.observeBlock(pendingRemoval)?.isAir == true) {
                session.state = PillarState.DESCEND_LAND
                return PillarProgress.Running("one temporary support was removed; waiting for the real landing")
            }
            if (active != null) {
                return PillarProgress.Running("waiting for another Core block break to finish")
            }
            // The pending block was not broken (for example a mod invalidated the attempt).
            // Re-evaluate it through the normal, bounded break path below instead of assuming it
            // disappeared or silently deleting the ownership record.
            session.currentPlacement = null
        }
        if (session.placedPositions.isEmpty()) {
            // The last ordinary break can leave the NPC briefly falling toward the original
            // terrain. Do not hand movement back to the parent task until vanilla physics has
            // produced a real landing; otherwise navigation receives an impossible air target.
            val feet = currentFeetCell(snapshot.position)
            val ground = world.observeBlock(NpcBlockPosition(feet.x, feet.y - 1, feet.z))
            if (!snapshot.onGround || ground?.isSolid != true) {
                return PillarProgress.Running("the final temporary block is gone; waiting for a normal landing")
            }
            session.lastResult = PillarResultCode.PILLAR_CLEANUP_COMPLETE
            return PillarProgress.CleanupComplete
        }
        if (!snapshot.onGround) {
            return PillarProgress.Running("waiting for stable footing before scaffold cleanup")
        }
        val beneath = currentFeetCell(snapshot.position).let { NpcBlockPosition(it.x, it.y - 1, it.z) }
        // After an interruption the body may be on the ground beside a too-high scaffold.
        // Only its highest recorded block is eligible from the side; normal Core reach/ray
        // checks still apply, and the same identity/occupancy checks below protect every break.
        val support = if (beneath in session.placedPositions) beneath else session.placedPositions.maxByOrNull { it.y }
            ?: return PillarProgress.CleanupIncomplete
        // Drops produced by the preceding normal block break do not occupy a scaffold. A player,
        // mob, boat, or minecart does; never remove a support while one of those is there.
        if (world.queryEntities(NpcEntityQuery(blockCenter(support), radius = 0.8, limit = 8)).any { it.typeId != "minecraft:item" }) {
            return PillarProgress.Running("another entity occupies the temporary scaffold")
        }
        val observation = world.observeBlock(support)
        if (observation == null || observation.isAir || !observation.isSolid) {
            session.lastResult = PillarResultCode.PILLAR_SUPPORT_LOST
            return PillarProgress.CleanupIncomplete
        }
        if (!matchesSupport(world, session, support)) {
            abortRelatedBreak(npc, session)
            return PillarProgress.CleanupIncomplete
        }
        val active = snapshot.blockBreak
        if (active != null && active.position == support) {
            val result = npc.continueBlockBreak()
            return result.toProgress("removing one temporary scaffold block")
        }
        if (active != null) {
            return PillarProgress.Running("waiting for another Core block break to finish")
        }
        lookAt(npc, blockCenter(support))
        val started = npc.startBlockBreak(support)
        if (started.isFailure()) {
            return PillarProgress.Failed(PillarResultCode.PILLAR_CLEANUP_INCOMPLETE, started.detail)
        }
        session.currentPlacement = support
        session.state = PillarState.DESCEND_BREAK
        return PillarProgress.Running("starting normal cleanup break for one temporary scaffold block")
    }

    private fun reconcileSupports(npc: NpcFacade, world: NpcWorldView, session: PillarSession): Boolean {
        for (position in session.placedPositions) {
            val pendingDescent = position == session.currentPlacement &&
                (session.state == PillarState.DESCEND_BREAK || session.state == PillarState.DESCEND_LAND)
            if (pendingDescent && world.observeBlock(position)?.isAir == true) continue
            if (!matchesSupport(world, session, position)) {
                abortRelatedBreak(npc, session)
                return false
            }
        }
        session.revalidateSupports = false
        return true
    }

    private fun matchesSupport(world: NpcWorldView, session: PillarSession, position: NpcBlockPosition): Boolean {
        val observed = world.observeBlock(position)
        if (observed == null || observed.isAir || !observed.isSolid) {
            session.lastResult = PillarResultCode.PILLAR_SUPPORT_LOST
            return false
        }
        val expected = session.placedBlockIds[position]
        if (expected == null || observed.blockId != expected) {
            session.lastResult = PillarResultCode.PILLAR_SUPPORT_CHANGED
            return false
        }
        return true
    }

    private fun abortRelatedBreak(npc: NpcFacade, session: PillarSession) {
        if (npc.snapshot().blockBreak?.position in session.placedPositions) npc.abortBlockBreak()
    }

    private fun validateStepBase(
        position: NpcPosition,
        onGround: Boolean,
        inWater: Boolean,
        inLava: Boolean,
        climbing: Boolean,
        riding: Boolean,
        session: PillarSession,
        world: NpcWorldView,
    ): PillarProgress {
        if (!onGround && !inWater && !inLava && !climbing && !riding) {
            if (++session.positioningTicks > MAX_POSITIONING_TICKS) {
                return fail(session, PillarResultCode.PILLAR_UNSAFE_ENVIRONMENT, "no stable landing before the bounded next-level deadline")
            }
            return PillarProgress.Running("waiting for normal landing before validating the next scaffold level")
        }
        if (session.placedPositions.size >= MAX_HEIGHT) {
            session.lastResult = PillarResultCode.PILLAR_HEIGHT_LIMIT
            return PillarProgress.Failed(session.lastResult)
        }
        val feet = currentFeetCell(position)
        when (val validation = validateBase(position, onGround, inWater, inLava, climbing, riding, feet, world)) {
            null -> Unit
            is PillarBeginResult.BlockedByLeaf -> return PillarProgress.BlockedByLeaf(validation.position)
            is PillarBeginResult.Failed -> {
                session.lastResult = validation.code
                return PillarProgress.Failed(validation.code)
            }
            is PillarBeginResult.Started -> error("base validation never starts a new session")
        }
        val headroom = headroomBlock(feet, world)
        if (headroom != null) {
            return when (headroom) {
                is PillarBeginResult.BlockedByLeaf -> PillarProgress.BlockedByLeaf(headroom.position)
                is PillarBeginResult.Failed -> {
                    session.lastResult = headroom.code
                    PillarProgress.Failed(headroom.code)
                }
                is PillarBeginResult.Started -> error("headroom validation never starts a new session")
            }
        }
        session.currentPlacement = feet
        session.positioningTicks = 0
        session.state = PillarState.POSITION_ON_SUPPORT
        return PillarProgress.Running("validated one legal pillar level")
    }

    private fun positionOnSupport(
        npc: NpcFacade,
        position: NpcPosition,
        session: PillarSession,
        world: NpcWorldView,
    ): PillarProgress {
        val placement = session.currentPlacement ?: return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE)
        if (++session.positioningTicks > MAX_POSITIONING_TICKS) {
            return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE, "could not center on $placement within $MAX_POSITIONING_TICKS ticks")
        }
        val support = NpcBlockPosition(placement.x, placement.y - 1, placement.z)
        if (world.observeBlock(support)?.isSolid != true) {
            return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE)
        }
        val offsetX = position.x - (support.x + 0.5)
        val offsetZ = position.z - (support.z + 0.5)
        if (hypot(offsetX, offsetZ) <= SUPPORT_CENTER_TOLERANCE) {
            npc.stopControl()
            session.state = PillarState.EQUIP_BLOCK
            return PillarProgress.Running("standing safely over the current support")
        }
        val turn = lookAt(npc, blockCenter(support))
        if (turn.isFailure()) {
            return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE)
        }
        val move = npc.applyControl(NpcControlInput(forward = 0.35F, strafe = 0.0F))
        return if (move.isFailure()) fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE) else PillarProgress.Running("centering over the support before jumping")
    }

    private fun equipMaterial(npc: NpcFacade, session: PillarSession): PillarProgress {
        val entries = npc.inventoryContents()
        // A live session may move its selected stack from the original inventory slot into the
        // active hotbar slot, but it never swaps in another stack mid-pillar. That invariant is
        // what makes its placement count and later task-item restoration auditable.
        val sourceEntry = entries.firstOrNull { entry ->
            (entry.slot == session.materialOriginalSlot || entry.slot == session.materialActiveSlot) &&
                entry.stack.itemId == session.materialItemId && entry.stack.count > 0
        } ?: return fail(session, PillarResultCode.PILLAR_NO_MATERIAL)
        val itemId = sourceEntry.stack.itemId ?: return fail(session, PillarResultCode.PILLAR_NO_MATERIAL)
        val materialClass = sourceEntry.knowledge.placeableBlock?.pillarMaterialClass ?: NpcPillarMaterialClass.FORBIDDEN
        val source = PillarMaterial(sourceEntry.slot, itemId, sourceEntry.stack.count, materialClass)
        if (source.slot != session.materialActiveSlot) {
            val swap = npc.swapInventorySlots(source.slot, session.materialActiveSlot)
            if (swap.isFailure()) {
                return fail(session, PillarResultCode.PILLAR_NO_MATERIAL)
            }
            session.materialOriginalSlot = source.slot
            session.materialItemId = source.itemId
        }
        val select = npc.selectHotbarSlot(session.materialActiveSlot)
        if (select.isFailure()) {
            return fail(session, PillarResultCode.PILLAR_NO_MATERIAL)
        }
        session.state = PillarState.START_JUMP
        return PillarProgress.Running("equipped an actual temporary building block")
    }

    private fun startJump(npc: NpcFacade, position: NpcPosition, session: PillarSession, world: NpcWorldView): PillarProgress {
        val placement = session.currentPlacement ?: return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE)
        if (currentFeetCell(position) != placement || world.observeBlock(placement)?.isAir != true) {
            return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE)
        }
        val supportTop = NpcPosition(placement.x + 0.5, placement.y.toDouble(), placement.z + 0.5)
        val look = lookAt(npc, supportTop)
        if (look.isFailure()) {
            return fail(session, PillarResultCode.PILLAR_PLACEMENT_FAILED)
        }
        val jump = npc.jump()
        if (jump.status != NpcActionStatus.SUCCEEDED) {
            return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE)
        }
        session.state = PillarState.WAIT_FOR_LEGAL_PLACEMENT_WINDOW
        return PillarProgress.Running("jumped; waiting until the feet cell is legally clear for placement")
    }

    private fun placeDuringJump(
        npc: NpcFacade,
        position: NpcPosition,
        verticalVelocity: Double,
        session: PillarSession,
        world: NpcWorldView,
    ): PillarProgress {
        val placement = session.currentPlacement ?: return fail(session, PillarResultCode.PILLAR_PLACEMENT_FAILED)
        if (verticalVelocity <= 0.0) {
            return retryOrFail(session, PillarResultCode.PILLAR_PLACEMENT_FAILED)
        }
        if (position.y < placement.y + LEGAL_PLACEMENT_BOTTOM_OFFSET) {
            return PillarProgress.Running("rising through the bounded legal placement window")
        }
        if (world.queryEntities(NpcEntityQuery(blockCenter(placement), radius = 0.65, limit = 8)).isNotEmpty()) {
            return fail(session, PillarResultCode.PILLAR_DESTINATION_OCCUPIED)
        }
        val held = npc.inventoryContents().firstOrNull { it.slot == session.materialActiveSlot }
        if (held?.stack?.itemId != session.materialItemId || held.stack.count <= 0) {
            return fail(session, PillarResultCode.PILLAR_NO_MATERIAL)
        }
        val supportTop = NpcPosition(placement.x + 0.5, placement.y.toDouble(), placement.z + 0.5)
        val look = lookAt(npc, supportTop)
        if (look.isFailure()) {
            return fail(session, PillarResultCode.PILLAR_PLACEMENT_FAILED)
        }
        session.expectedMaterialCountAfterPlacement = held.stack.count - 1
        val placed = npc.placeHeldBlock(NpcBlockPlacement(placement, NpcBlockFace.UP), io.samcnpc.core.api.NpcHand.MAIN)
        if (placed.status != NpcActionStatus.SUCCEEDED) {
            session.expectedMaterialCountAfterPlacement = null
            return fail(
                session,
                if (placed.status == NpcActionStatus.REJECTED) PillarResultCode.PILLAR_PLACEMENT_DENIED else PillarResultCode.PILLAR_PLACEMENT_FAILED,
                placed.detail,
            )
        }
        session.state = PillarState.VERIFY_PLACEMENT
        // Placement and consumption are synchronous Core operations on this server thread.
        // Verify before yielding: the next entity tick may legitimately pick up another log
        // into this very stack. A later count is not a receipt for this placement.
        return verifyPlacement(npc, session, world)
    }

    private fun verifyPlacement(npc: NpcFacade, session: PillarSession, world: NpcWorldView): PillarProgress {
        val placement = session.currentPlacement ?: return fail(session, PillarResultCode.PILLAR_PLACEMENT_DESYNC)
        val actual = world.observeBlock(placement)
        val held = npc.inventoryContents().firstOrNull { it.slot == session.materialActiveSlot }
        val expectedCount = session.expectedMaterialCountAfterPlacement
            ?: return fail(session, PillarResultCode.PILLAR_PLACEMENT_DESYNC)
        val consumedFromSelectedMaterial = if (held?.stack?.isEmpty == true) {
            expectedCount == 0
        } else {
            held?.stack?.itemId == session.materialItemId &&
                held.stack.count == expectedCount
        }
        if (actual?.isSolid != true) {
            return fail(session, PillarResultCode.PILLAR_PLACEMENT_DESYNC, "Core accepted placement at $placement but observed block is $actual")
        }
        // Even a legacy in-flight verification or a faulty inventory receipt must retain the
        // accepted world mutation, otherwise cleanup cannot descend past this last level.
        if (placement !in session.placedPositions) {
            session.placedPositions.add(placement)
        }
        session.placedBlockIds[placement] = actual.blockId
        if (!consumedFromSelectedMaterial) {
            return fail(session, PillarResultCode.PILLAR_PLACEMENT_DESYNC,
                "placement=$placement expected=${session.materialItemId}x$expectedCount slot=${session.materialActiveSlot} actual=${held?.stack}")
        }
        session.expectedMaterialCountAfterPlacement = null
        session.state = PillarState.WAIT_FOR_LANDING
        return PillarProgress.Running("verified actual world placement and inventory consumption")
    }

    private fun waitForLanding(
        npc: NpcFacade,
        position: NpcPosition,
        onGround: Boolean,
        session: PillarSession,
        world: NpcWorldView,
    ): PillarProgress {
        val placement = session.currentPlacement ?: return fail(session, PillarResultCode.PILLAR_SUPPORT_LOST)
        if (!matchesSupport(world, session, placement)) {
            return fail(session, session.lastResult)
        }
        val standing = placement.copy(y = placement.y + 1)
        if (!onGround || currentFeetCell(position) != standing || position.y < placement.y + LANDING_BOTTOM_OFFSET) {
            if (++session.positioningTicks > MAX_POSITIONING_TICKS) {
                npc.stopControl()
                return fail(session, PillarResultCode.PILLAR_INTERRUPTED, "could not return to verified scaffold footing within $MAX_POSITIONING_TICKS steps")
            }
            if (!onGround) return PillarProgress.Running("waiting for normal landing before recovering scaffold footing")
            val destination = NpcPosition(standing.x + 0.5, standing.y.toDouble(), standing.z + 0.5)
            val observed = world.observeStandingSpace(destination)
            if (observed == null || !observed.clear || !observed.supported || observed.inFluid) {
                return fail(session, PillarResultCode.PILLAR_NO_SAFE_BASE, "recorded scaffold top is not a safe standing space")
            }
            val route = npc.navigateTo(NpcNavigationRequest(destination, 1.0F, 0.25))
            if (route.isFailure()) return fail(session, PillarResultCode.PILLAR_INTERRUPTED, route.detail)
            return PillarProgress.Running("returning to the verified scaffold top after interrupted movement")
        }
        npc.stopControl()
        session.positioningTicks = 0
        session.lastResult = PillarResultCode.PILLAR_LEVEL_COMPLETED
        workReachProgress(npc, world, session)?.let { return it }
        session.currentPlacement = null
        session.expectedMaterialCountAfterPlacement = null
        session.state = PillarState.VALIDATE_BASE
        return PillarProgress.Running("landed safely; re-evaluating the target before any further elevation")
    }

    private fun restoreTaskItem(npc: NpcFacade, session: PillarSession): PillarProgress {
        if (session.materialActiveSlot == session.originalSelectedHotbarSlot && session.materialOriginalSlot != session.materialActiveSlot) {
            val restore = npc.swapInventorySlots(session.materialOriginalSlot, session.materialActiveSlot)
            if (restore.isFailure()) {
                return fail(session, PillarResultCode.PILLAR_RECOVERY_REQUIRED)
            }
        }
        val select = npc.selectHotbarSlot(session.originalSelectedHotbarSlot)
        if (select.isFailure()) {
            return fail(session, PillarResultCode.PILLAR_RECOVERY_REQUIRED)
        }
        session.lastResult = PillarResultCode.PILLAR_TARGET_REACHED
        return PillarProgress.TargetReached
    }

    private fun validateBase(
        position: NpcPosition,
        onGround: Boolean,
        inWater: Boolean,
        inLava: Boolean,
        climbing: Boolean,
        riding: Boolean,
        feet: NpcBlockPosition,
        world: NpcWorldView,
    ): PillarBeginResult? {
        if (!onGround || inWater || inLava || climbing || riding) {
            return PillarBeginResult.Failed(PillarResultCode.PILLAR_UNSAFE_ENVIRONMENT)
        }
        if (world.observeBlock(feet)?.isAir != true || world.observeBlock(NpcBlockPosition(feet.x, feet.y - 1, feet.z))?.isSolid != true) {
            return PillarBeginResult.Failed(PillarResultCode.PILLAR_NO_SAFE_BASE)
        }
        val hazard = world.observeBlock(feet)?.blockId?.substringAfter(':')
        if (hazard in HAZARD_PATHS) {
            return PillarBeginResult.Failed(PillarResultCode.PILLAR_UNSAFE_ENVIRONMENT)
        }
        return null
    }

    private fun headroomBlock(feet: NpcBlockPosition, world: NpcWorldView): PillarBeginResult? {
        val head = NpcBlockPosition(feet.x, feet.y + 1, feet.z)
        val jumpHead = NpcBlockPosition(feet.x, feet.y + 2, feet.z)
        for (candidate in listOf(head, jumpHead)) {
            val observed = world.observeBlock(candidate) ?: return PillarBeginResult.Failed(PillarResultCode.PILLAR_NO_HEADROOM)
            if (!observed.isAir) {
                val snowOnLeaf = observed.blockId == "minecraft:snow" &&
                    world.observeBlock(NpcBlockPosition(candidate.x, candidate.y - 1, candidate.z))?.blockId
                        ?.substringAfter(':')
                        ?.endsWith("_leaves") == true
                return if (observed.blockId.substringAfter(':').endsWith("_leaves") || snowOnLeaf) {
                    PillarBeginResult.BlockedByLeaf(candidate)
                } else {
                    PillarBeginResult.Failed(PillarResultCode.PILLAR_NO_HEADROOM)
                }
            }
        }
        return null
    }

    private fun permittedMaterials(npc: NpcFacade): List<PillarMaterial> = npc.inventoryContents()
        .mapNotNull { entry ->
            val profile = entry.knowledge.placeableBlock ?: return@mapNotNull null
            val itemId = entry.stack.itemId ?: return@mapNotNull null
            if (entry.stack.count <= 0 || !isPermittedMaterial(entry.knowledge)) {
                return@mapNotNull null
            }
            PillarMaterial(entry.slot, itemId, entry.stack.count, profile.pillarMaterialClass)
        }
        .sortedWith(
            compareBy<PillarMaterial> { materialPriority(it.materialClass) }
                .thenByDescending { it.count }
                .thenBy { it.slot },
        )

    private fun materialPriority(materialClass: NpcPillarMaterialClass): Int = when (materialClass) {
        NpcPillarMaterialClass.PREFERRED -> 0
        NpcPillarMaterialClass.ALLOWED -> 1
        NpcPillarMaterialClass.AVOID -> 2
        NpcPillarMaterialClass.FORBIDDEN -> 3
    }

    private fun estimateLevels(position: NpcPosition, target: NpcBlockPosition): Int {
        val horizontal = hypot(position.x - target.x - 0.5, position.z - target.z - 0.5)
        if (horizontal >= BREAK_REACH) {
            return 0
        }
        val verticalReach = sqrt(max(0.0, BREAK_REACH * BREAK_REACH - horizontal * horizontal))
        return ceil(target.y + 0.5 - position.y - verticalReach).toInt().coerceAtLeast(1)
    }

    private fun workReachProgress(npc: NpcFacade, world: NpcWorldView, session: PillarSession): PillarProgress? {
        val snapshot = npc.snapshot()
        val target = session.targetPosition
        val center = blockCenter(target)
        val dx = snapshot.position.x - center.x
        val dy = snapshot.position.y - center.y
        val dz = snapshot.position.z - center.z
        if (dx * dx + dy * dy + dz * dz > BREAK_REACH * BREAK_REACH) {
            return null
        }
        val direction = NpcVector(center.x - snapshot.eyePosition.x, center.y - snapshot.eyePosition.y, center.z - snapshot.eyePosition.z)
        val distance = hypot(hypot(direction.x, direction.z), direction.y)
        val hit = world.raycast(NpcRaycastRequest(snapshot.eyePosition, direction, distance + 0.01)) as? NpcRaycastResult.BlockHit
        if (hit?.position == target) {
            session.state = PillarState.RESTORE_TASK_ITEM
            return PillarProgress.Running("supplied work is in reach and visible; restoring the original work item")
        }
        // More height does not clear a leaf between an already reachable block and the eye.
        // Give the parent the exact obstruction while retaining the verified work scaffold.
        if (hit != null && world.isLeafOrSupportedSnowObstacle(hit.position)) {
            return PillarProgress.BlockedByLeaf(hit.position)
        }
        return fail(session, PillarResultCode.PILLAR_TARGET_OBSTRUCTED, "in-reach target $target is obscured by ${hit?.position}", hit?.position)
    }

    private fun retryOrFail(session: PillarSession, code: PillarResultCode): PillarProgress {
        if (session.retries >= MAX_RETRIES) {
            return fail(session, code)
        }
        session.retries += 1
        session.state = PillarState.VALIDATE_BASE
        session.currentPlacement = null
        session.expectedMaterialCountAfterPlacement = null
        return PillarProgress.Running("the legal placement window closed; returning to stable footing before one bounded retry")
    }

    private fun fail(session: PillarSession, code: PillarResultCode, detail: String = "", obstruction: NpcBlockPosition? = null): PillarProgress {
        session.lastResult = code
        return PillarProgress.Failed(code, detail, obstruction)
    }

    private fun lookAt(npc: NpcFacade, target: NpcPosition): NpcActionResult {
        val snapshot = npc.snapshot()
        val dx = target.x - snapshot.position.x
        val dy = target.y - snapshot.position.y
        val dz = target.z - snapshot.position.z
        val horizontal = hypot(dx, dz)
        val yaw = (Math.toDegrees(atan2(dz, dx)) - 90.0).toFloat()
        val pitch = if (horizontal <= 0.0001) snapshot.pitch else (-Math.toDegrees(atan2(dy, horizontal))).toFloat()
        return npc.setLookRotation(io.samcnpc.core.api.NpcLookRotation(yaw, pitch))
    }

    private fun currentFeetCell(position: NpcPosition): NpcBlockPosition =
        NpcBlockPosition(floor(position.x).toInt(), floor(position.y).toInt(), floor(position.z).toInt())

    private fun adjacentSupport(position: NpcPosition, world: NpcWorldView): NpcBlockPosition? {
        val feet = currentFeetCell(position)
        val candidates = ArrayList<NpcBlockPosition>(9)
        for (dx in -1..1) for (dz in -1..1) {
            val candidate = NpcBlockPosition(feet.x + dx, feet.y, feet.z + dz)
            if (hypot(position.x - candidate.x - 0.5, position.z - candidate.z - 0.5) > 1.15) continue
            if (world.observeBlock(candidate)?.isAir != true) continue
            if (world.observeBlock(NpcBlockPosition(candidate.x, candidate.y - 1, candidate.z))?.isSolid != true) continue
            candidates.add(candidate)
        }
        return candidates.minWithOrNull(compareBy<NpcBlockPosition> {
            hypot(position.x - it.x - 0.5, position.z - it.z - 0.5)
        }.thenBy { it.x }.thenBy { it.z })
    }

    private fun blockCenter(position: NpcBlockPosition): NpcPosition =
        NpcPosition(position.x + 0.5, position.y + 0.5, position.z + 0.5)

    private fun NpcActionResult.isFailure(): Boolean =
        status == NpcActionStatus.REJECTED || status == NpcActionStatus.FAILED || status == NpcActionStatus.UNSUPPORTED

    private fun NpcActionResult.toProgress(detail: String): PillarProgress =
        if (isFailure()) PillarProgress.Failed(PillarResultCode.PILLAR_CLEANUP_INCOMPLETE) else PillarProgress.Running(detail)

    private data class PillarMaterial(
        val slot: Int,
        val itemId: String,
        val count: Int,
        val materialClass: NpcPillarMaterialClass,
    )

    internal sealed interface PillarBeginResult {
        data class Started(val session: PillarSession) : PillarBeginResult
        data class Failed(val code: PillarResultCode) : PillarBeginResult
        data class BlockedByLeaf(val position: NpcBlockPosition) : PillarBeginResult
    }

    sealed interface PillarProgress {
        data class Running(val detail: String) : PillarProgress
        data object TargetReached : PillarProgress
        data object TargetGone : PillarProgress
        data class BlockedByLeaf(val position: NpcBlockPosition) : PillarProgress
        data class Failed(val code: PillarResultCode, val detail: String = "", val obstruction: NpcBlockPosition? = null) : PillarProgress
        data object CleanupComplete : PillarProgress
        data object CleanupIncomplete : PillarProgress
    }

    private val HAZARD_PATHS = setOf("lava", "fire", "soul_fire", "cactus", "magma_block", "powder_snow")
    private const val HOTBAR_SIZE = 9
    private const val MAX_POSITIONING_TICKS = 40
}
