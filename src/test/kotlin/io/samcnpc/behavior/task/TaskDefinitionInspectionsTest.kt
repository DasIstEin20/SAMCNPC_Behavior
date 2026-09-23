package io.samcnpc.behavior.task

import com.google.gson.*
import io.samcnpc.behavior.api.*
import io.samcnpc.core.api.NpcBlockPosition
import io.samcnpc.core.api.NpcPosition
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class TaskDefinitionInspectionsTest {
    @Test fun allCurrentOperationFixturesPreserveTheExistingPersistenceRepresentation() {
        val directory = Path.of(checkNotNull(javaClass.getResource("/operation-documents")).toURI())
        val paths = Files.walk(directory).use { it.filter { p -> p.toString().endsWith(".json") }.sorted().toList() }
        assertEquals(47, paths.size)
        for (path in paths) {
            val original = OperationDocumentApi.decodeOrder(Files.readString(path))
            assertIs<OperationDocumentResult.Accepted<OperationOrder>>(original, path.toString())
            val definition = TaskPublicOrders.definition(original.value)
            val view = TaskDefinitionInspections.capture(definition)
            val document = JsonObject()
            document.addProperty("documentVersion", 1)
            document.addProperty("type", view.operationId)
            document.addProperty("definitionVersion", view.definitionVersion)
            document.add("parameters", json(view.parameters))
            val decoded = OperationDocumentApi.decodeOrder(document.toString())
            assertIs<OperationDocumentResult.Accepted<OperationOrder>>(decoded, path.fileName.toString() + ": " + decoded)
            // Independent existing persistence codec verifies every field, including resolved defaults.
            assertEquals(TaskCodec.writeDefinition(definition),
                TaskCodec.writeDefinition(TaskPublicOrders.definition(decoded.value)), path.fileName.toString())
        }
    }

    @Test fun legacyDefinitionsRetainTheirVersionsAndNeverInventAnAnchorOrTactics() {
        val delivery = DeliveryTaskDefinition("minecraft:overworld", NpcBlockPosition(1, 64, 0), "minecraft:stone", 8)
        val deliveryTag = TaskCodec.writeDefinition(delivery)
        val view = TaskDefinitionInspections.capture(TaskCodec.readDefinition(deliveryTag))
        assertEquals(1, view.definitionVersion)
        assertSame(OperationValue.Absent, view.parameters.fields["anchor"])
        assertEquals(deliveryTag, TaskCodec.writeDefinition(delivery))
        val attack = AttackTaskDefinition("minecraft:overworld", UUID.randomUUID(), NpcPosition(0.0, 64.0, 0.0), version = 1)
        val attackView = TaskDefinitionInspections.capture(TaskCodec.readDefinition(TaskCodec.writeDefinition(attack)))
        assertEquals(1, attackView.definitionVersion)
        val tactics = assertIs<OperationValue.Record>(attackView.parameters.fields["tactics"])
        assertEquals(OperationValue.Text("CURRENT"), tactics.fields["preference"])
        assertEquals(OperationValue.Flag(false), tactics.fields["heal"])
        val currentWood = assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(
            checkNotNull(javaClass.getResource("/operation-documents/lumberjack.json")).readText())).value
        val wood = assertIs<LumberjackTaskDefinition>(TaskPublicOrders.definition(currentWood)).copy(version = 1)
        val woodView = TaskDefinitionInspections.capture(TaskCodec.readDefinition(TaskCodec.writeDefinition(wood)))
        assertEquals(1, woodView.definitionVersion)
        assertSame(OperationValue.Absent, woodView.parameters.fields["supplySources"])
        assertSame(OperationValue.Absent, woodView.parameters.fields["replant"])
    }

    @Test fun nestedInspectionValuesAreDetachedBoundedAndReadOnly() {
        val members = mutableListOf<OperationValue>(OperationValue.Text("minecraft:stone"))
        val sequence = OperationValue.Sequence(members)
        val fields = linkedMapOf<String, OperationValue>("items" to sequence)
        val record = OperationValue.Record(fields)
        members.clear(); fields.clear()
        assertEquals(1, record.fields.size)
        assertEquals(1, sequence.values.size)
        assertFailsWith<UnsupportedOperationException> { (record.fields as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (sequence.values as MutableList).clear() }
        assertFailsWith<IllegalArgumentException> { OperationValue.Sequence(List(129) { OperationValue.Absent }) }
        assertFailsWith<IllegalArgumentException> { OperationValue.Decimal(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { OperationValue.Text("x".repeat(257)) }
    }

    @Test fun miningInspectionPreservesDescentAndStrictLegacyShape() {
        for ((version,stepDown) in listOf(1 to 0,2 to 0,2 to 1)) {
            val g=TunnelGeometry(NpcBlockPosition(4,64,0),TunnelDirection.EAST,1,3,4,stepDown)
            val d=MiningTaskDefinition("minecraft:overworld",MiningWorkOrder(WorkArea(g.bounds()),MiningMethod.TUNNEL,
                WorkResourceIds(listOf("minecraft:stone")),tunnel=g),WorkResourceIds(listOf("minecraft:cobblestone")),
                ContainerChoices(listOf(NpcBlockPosition(-2,64,1))),1,MiningCounting.CLEARED_VOLUME,NpcPosition(0.5,64.0,0.5),version=version)
            val view=TaskDefinitionInspections.capture(d)
            val parameters=json(view.parameters).asJsonObject
            val tunnel=parameters["work"].asJsonObject["tunnel"].asJsonObject
            assertEquals(version>=2,tunnel.has("stepDown"))
            if (version>=2) assertEquals(stepDown,tunnel["stepDown"].asInt)
            val document=JsonObject()
            document.addProperty("documentVersion",1);document.addProperty("type",view.operationId)
            document.addProperty("definitionVersion",view.definitionVersion);document.add("parameters",parameters)
            val decoded=assertIs<OperationDocumentResult.Accepted<OperationOrder>>(OperationDocumentApi.decodeOrder(document.toString()))
            assertEquals(d.work,assertIs<MiningTaskDefinition>(TaskPublicOrders.definition(decoded.value)).work)
        }
    }

    private fun json(value: OperationValue): JsonElement = when (value) {
        OperationValue.Absent -> JsonNull.INSTANCE
        is OperationValue.Text -> JsonPrimitive(value.value)
        is OperationValue.Whole -> JsonPrimitive(value.value)
        is OperationValue.Decimal -> JsonPrimitive(value.value)
        is OperationValue.Flag -> JsonPrimitive(value.value)
        is OperationValue.Sequence -> JsonArray().also { array -> value.values.forEach { array.add(json(it)) } }
        is OperationValue.Record -> JsonObject().also { obj -> value.fields.forEach { (key, child) -> obj.add(key, json(child)) } }
    }
}
