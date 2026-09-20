package io.samcnpc.behavior.api

import com.google.gson.JsonParser
import io.samcnpc.behavior.operation.*
import io.samcnpc.core.api.NpcActionStatus
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OperationVariantDocumentTest {
    @Test fun variantCorpusPreservesExplicitChoicesAndSameSemanticValidator() {
        val directory = Path.of(checkNotNull(javaClass.getResource("/operation-documents/variants")).toURI())
        val paths = Files.list(directory).use { it.filter { path -> path.toString().endsWith(".json") }.sorted().toList() }
        assertEquals(30, paths.size)
        for (path in paths) {
            val json = Files.readString(path)
            val decoded = OperationDocumentApi.decodeOrder(json)
            assertIs<OperationDocumentResult.Accepted<OperationOrder>>(decoded, path.fileName.toString() + ": " + decoded)
            assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(decoded.value).status)
            val p = JsonParser.parseString(json).asJsonObject.getAsJsonObject("parameters")
            when (val order = decoded.value) {
                is OperationHarvestOrder.Mining -> {
                    assertEquals(p.obj("work").text("method"), order.work.method.name)
                    assertEquals(p.text("counting"), order.counting.name)
                }
                is OperationHarvestOrder.Farm -> {
                    assertEquals(p.obj("work").text("mode"), order.work.mode.name)
                    assertEquals(p.obj("work").text("crop"), order.work.crop.name)
                }
                is OperationHarvestOrder.Planting -> {
                    assertEquals(p.obj("work").text("mode"), order.work.mode.name)
                    assertEquals(p.obj("work").text("species"), order.work.species.name)
                }
                is OperationCombatOrder.Patrol -> assertEquals(p.text("reaction"), order.reaction.name)
                is OperationHarvestOrder.Lumberjack -> assertEquals(OperationPlantingMode.GAPS, checkNotNull(order.replant).work.mode)
                is OperationHarvestOrder.Food -> when (p.obj("work").text("kind")) {
                    "DROPS" -> assertIs<OperationFoodWork.Drops>(order.work)
                    "BERRIES" -> assertIs<OperationFoodWork.Berries>(order.work)
                    "HUNT" -> assertIs<OperationFoodWork.Hunt>(order.work)
                }
                is OperationInventoryOrder -> when (p.obj("work").text("kind")) {
                    "UNLOAD" -> assertIs<OperationInventoryWork.Unload>(order.work)
                    "PICKUP" -> assertIs<OperationInventoryWork.Pickup>(order.work)
                }
                else -> error("Unexpected fixture family")
            }
        }
    }
}
