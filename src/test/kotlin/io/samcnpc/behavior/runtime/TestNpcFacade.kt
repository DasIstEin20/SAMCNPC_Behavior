package io.samcnpc.behavior.runtime

import io.samcnpc.core.api.*
import java.util.UUID

/** Unused capabilities fail loudly; each lifecycle test implements only effects it exercises. */
internal open class TestNpcFacade : NpcFacade {
    override val npcUuid: UUID = UUID(0, 1)
    override fun snapshot(): NpcSnapshot = error("unexpected snapshot")
    override fun inventoryContents(): List<NpcInventoryEntry> = error("unexpected inventory query")
    override fun equipmentContents(): NpcEquipmentSnapshot = error("unexpected equipment query")
    override fun equipmentKnowledge(): NpcEquipmentKnowledge = error("unexpected equipment knowledge")
    override fun worldView(): NpcWorldView = error("unexpected world query")
    override fun equipFromInventory(slot: Int, destination: NpcEquipmentDestination): NpcActionResult = error("unexpected equip")
    override fun lookAtEntity(entityUuid: UUID): NpcActionResult = error("unexpected look")
    override fun setLookRotation(rotation: NpcLookRotation): NpcActionResult = error("unexpected rotation")
    override fun applyControl(input: NpcControlInput): NpcActionResult = error("unexpected control")
    override fun navigateTo(position: NpcPosition, speedMultiplier: Float): NpcActionResult = error("unexpected navigation")
    override fun stopControl(): NpcActionResult = error("unexpected stop")
    override fun jump(): NpcActionResult = error("unexpected jump")
    override fun selectHotbarSlot(slot: Int): NpcActionResult = error("unexpected slot")
    override fun attackEntity(entityUuid: UUID): NpcActionResult = error("unexpected attack")
    override fun startRangedAttack(entityUuid: UUID, hand: NpcHand): NpcActionResult = error("unexpected ranged attack")
    override fun cancelRangedAttack(): NpcActionResult = error("unexpected ranged cancel")
    override fun pickupItem(itemEntityUuid: UUID): NpcActionResult = error("unexpected pickup")
    override fun dropInventoryStack(slot: Int, count: Int): NpcActionResult = error("unexpected drop")
    override fun moveInventoryStack(sourceSlot: Int, destinationSlot: Int, count: Int): NpcActionResult = error("unexpected inventory move")
    override fun swapInventorySlots(firstSlot: Int, secondSlot: Int): NpcActionResult = error("unexpected swap")
    override fun startBlockBreak(position: NpcBlockPosition): NpcActionResult = error("unexpected break")
    override fun continueBlockBreak(): NpcActionResult = error("unexpected continue break")
    override fun abortBlockBreak(): NpcActionResult = error("unexpected abort break")
    override fun useItemInAir(hand: NpcHand): NpcActionResult = error("unexpected air use")
    override fun useItemOnBlock(hit: NpcBlockHit, hand: NpcHand): NpcActionResult = error("unexpected block use")
    override fun interactEntity(hit: NpcEntityHit, hand: NpcHand): NpcActionResult = error("unexpected entity interaction")
    override fun placeHeldBlock(placement: NpcBlockPlacement, hand: NpcHand): NpcActionResult = error("unexpected placement")
    override fun useInteractiveBlock(position: NpcBlockPosition): NpcActionResult = error("unexpected interaction")
    override fun moveInventoryToBlockContainer(inventorySlot: Int, destination: NpcBlockContainerSlot, count: Int): NpcActionResult = error("unexpected deposit")
    override fun moveBlockContainerToInventory(source: NpcBlockContainerSlot, count: Int): NpcActionResult = error("unexpected withdrawal")
    override fun startItemUse(hand: NpcHand): NpcActionResult = error("unexpected item use")
    override fun continueItemUse(): NpcActionResult = error("unexpected continue item use")
    override fun releaseItemUse(): NpcActionResult = error("unexpected release item use")
    override fun cancelItemUse(): NpcActionResult = error("unexpected cancel item use")
}
