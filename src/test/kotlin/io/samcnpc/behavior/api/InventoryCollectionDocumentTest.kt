package io.samcnpc.behavior.api

import com.google.gson.JsonParser
import io.samcnpc.core.api.NpcBlockPosition
import org.junit.jupiter.api.Test
import kotlin.test.*

class InventoryCollectionDocumentTest {
    private fun document() = JsonParser.parseString("""
        {"documentVersion":1,"type":"samcnpc:inventory_work","definitionVersion":2,
         "parameters":{"dimensionId":"minecraft:overworld","anchor":{"x":0.5,"y":64,"z":0.5},
         "work":{"kind":"COLLECT","source":{"x":-2,"y":64,"z":0}}}}
    """).asJsonObject

    @Test fun collectionIsVersionedClosedAndHasBoundedDefaults() {
        val decoded = assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(document().toString()))
        val order = assertIs<OperationInventoryOrder>(decoded.value)
        val work = assertIs<OperationInventoryWork.Collect>(order.work)
        assertEquals(NpcBlockPosition(-2, 64, 0), work.source)
        assertEquals(2304, work.maxItems)
        assertEquals(order.anchor, order.returnTo)
        assertEquals(2, order.type.definitionVersion)
        assertEquals(4, OperationCatalogApi.snapshot().catalogVersion)
        for (version in listOf(0, 1, 3)) {
            val invalid = document(); invalid.addProperty("definitionVersion", version)
            assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(invalid.toString()))
        }
        for (limit in listOf(0, 2305)) {
            val invalid = document(); invalid["parameters"].asJsonObject["work"].asJsonObject.addProperty("maxItems", limit)
            assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(invalid.toString()))
        }
        val unknown = document(); unknown["parameters"].asJsonObject["work"].asJsonObject.addProperty("itemId", "minecraft:diamond")
        assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(unknown.toString()))
    }

    @Test fun originalVersionOneSupplyRemainsValidWithoutAcceptingNewWorkKinds() {
        val original = checkNotNull(javaClass.getResource("/operation-documents/inventory_work.json")).readText()
        val decoded = assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(original))
        assertIs<OperationInventoryWork.Supply>(assertIs<OperationInventoryOrder>(decoded.value).work)
        val changed = JsonParser.parseString(original).asJsonObject
        changed.addProperty("definitionVersion", 1)
        changed["parameters"].asJsonObject.add("work", document()["parameters"].asJsonObject["work"])
        assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(changed.toString()))
    }
}
