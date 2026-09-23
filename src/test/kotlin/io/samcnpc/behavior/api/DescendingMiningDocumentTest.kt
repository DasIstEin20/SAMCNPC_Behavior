package io.samcnpc.behavior.api

import com.google.gson.JsonParser
import io.samcnpc.behavior.task.TaskPublicOrders
import io.samcnpc.behavior.task.MiningTaskDefinition
import org.junit.jupiter.api.Test
import kotlin.test.*

class DescendingMiningDocumentTest {
    private fun document() = JsonParser.parseString("""
        {"documentVersion":1,"type":"samcnpc:mine","definitionVersion":2,
         "parameters":{"dimensionId":"minecraft:overworld","anchor":{"x":-17.5,"y":63,"z":-23.5},
         "work":{"method":"TUNNEL","resources":["minecraft:stone"],
           "area":{"bounds":{"min":{"x":-15,"y":59,"z":-21},"max":{"x":-11,"y":65,"z":-20}}},
           "tunnel":{"origin":{"x":-15,"y":63,"z":-21},"direction":"EAST","width":2,"height":3,"length":5,"stepDown":1}},
         "outputs":["minecraft:cobblestone"],"destinations":{"positions":[{"x":-17,"y":63,"z":-23}]},
         "quantity":1,"counting":"CLEARED_VOLUME"}}
    """).asJsonObject

    @Test fun publicVersionTwoCarriesBoundedDescentIntoTheExistingTask() {
        val decoded=assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(document().toString()))
        val order=assertIs<OperationHarvestOrder.Mining>(decoded.value)
        assertEquals(1,order.work.tunnel?.stepDown);assertEquals(2,order.type.definitionVersion)
        val definition=assertIs<MiningTaskDefinition>(TaskPublicOrders.definition(order))
        assertNull(definition.validationProblem());assertEquals(30,definition.clearanceCells)
        for (value in listOf(-1,2,Int.MAX_VALUE)) {
            val invalid=document();invalid["parameters"].asJsonObject["work"].asJsonObject["tunnel"].asJsonObject.addProperty("stepDown",value)
            assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(invalid.toString()))
        }
        val short=document();short["parameters"].asJsonObject["work"].asJsonObject["tunnel"].asJsonObject.addProperty("height",2)
        assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(short.toString()))
    }

    @Test fun legacyDocumentsRemainStrictAndDoNotAcquireDescentByAnUnknownField() {
        val original=checkNotNull(javaClass.getResource("/operation-documents/mine.json")).readText()
        assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(original))
        val legacy=document();legacy.addProperty("definitionVersion",1)
        assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(legacy.toString()))
        val work=legacy["parameters"].asJsonObject["work"].asJsonObject
        work["tunnel"].asJsonObject.addProperty("stepDown",0)
        work["area"].asJsonObject["bounds"].asJsonObject["min"].asJsonObject.addProperty("y",63)
        assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(legacy.toString()))
        work["tunnel"].asJsonObject.remove("stepDown")
        val decoded=assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(legacy.toString()))
        assertEquals(0,assertIs<OperationHarvestOrder.Mining>(decoded.value).work.tunnel?.stepDown)
        legacy.addProperty("definitionVersion",2)
        val current=assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(legacy.toString()))
        assertEquals(0,assertIs<OperationHarvestOrder.Mining>(current.value).work.tunnel?.stepDown)
    }
}
