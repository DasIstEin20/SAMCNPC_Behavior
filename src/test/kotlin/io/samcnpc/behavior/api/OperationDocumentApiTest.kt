package io.samcnpc.behavior.api

import com.google.gson.*
import io.samcnpc.behavior.operation.OperationJsonShape
import io.samcnpc.core.api.NpcActionStatus
import org.junit.jupiter.api.Test
import kotlin.test.*

class OperationDocumentApiTest {
    private fun fixture(type: OperationType): JsonObject = JsonParser.parseString(
        checkNotNull(javaClass.getResource("/operation-documents/" + type.operationId.substringAfter(':') + ".json")).readText()).asJsonObject
    private fun accepted(document: JsonObject): OperationOrder {
        val result = OperationDocumentApi.decodeOrder(document.toString())
        assertIs<OperationDocumentResult.Accepted<OperationOrder>>(result, result.toString())
        assertEquals(NpcActionStatus.SUCCEEDED, OperationSupervisionApi.validateOrder(result.value).status)
        return result.value
    }
    private fun rejected(document: JsonObject) = assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(document.toString()))

    @Test fun everyFamilyDecodesAndItsBudgetLimitsAreEnforced() {
        for (type in OperationType.entries) {
            val document = fixture(type)
            assertEquals(type, accepted(document).type)
            for (attempts in listOf(1, 8)) {
                val boundary = document.deepCopy()
                boundary.getAsJsonObject("parameters").add("budget", JsonObject().also { it.addProperty("attempts", attempts) })
                assertEquals(attempts, accepted(boundary).budget.attempts)
            }
            for (attempts in listOf(0, 9)) {
                val bad = document.deepCopy()
                bad.getAsJsonObject("parameters").add("budget", JsonObject().also { it.addProperty("attempts", attempts) })
                rejected(bad)
            }
            val missing = document.deepCopy(); missing.getAsJsonObject("parameters").remove("dimensionId"); rejected(missing)
            val unknown = document.deepCopy(); unknown.getAsJsonObject("parameters").addProperty("command", "say arbitrary"); rejected(unknown)
            val version = document.deepCopy(); version.addProperty("definitionVersion", type.definitionVersion + 1); rejected(version)
        }
    }

    @Test fun catalogIsDeeplyImmutableCompleteAndReferencesResolve() {
        val catalog = OperationCatalogApi.snapshot()
        assertSame(catalog, OperationCatalogApi.snapshot())
        assertEquals(OperationType.entries.toList(), catalog.operations.map { it.type })
        assertEquals(8, catalog.changes.size)
        assertFailsWith<UnsupportedOperationException> { (catalog.operations as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (catalog.shapes as MutableMap).clear() }
        fun visit(input: OperationInput) {
            when (input) {
                is OperationInput.Record -> {
                    assertEquals(input.fields.size, input.fields.map { it.name }.distinct().size)
                    assertFailsWith<UnsupportedOperationException> { (input.fields as MutableList).clear() }
                    input.fields.forEach { visit(it.input) }
                }
                is OperationInput.Sequence -> visit(input.item)
                is OperationInput.Choice -> assertFailsWith<UnsupportedOperationException> { (input.values as MutableList).clear() }
                is OperationInput.Reference -> assertTrue(catalog.shapes.containsKey(input.name), input.name)
                is OperationInput.Alternatives -> input.names.forEach { assertTrue(catalog.shapes.containsKey(it), it) }
                else -> Unit
            }
        }
        catalog.shapes.values.forEach(::visit)
        catalog.changes.values.forEach(::visit)
        assertEquals(listOf("EXTEND_TIME", "TACTICS"), catalog.operations.single { it.type == OperationType.DEFEND }.amendments)
        assertFalse("LOGISTICS" in catalog.operations.single { it.type == OperationType.ATTACK }.amendments)
    }

    @Test fun defaultsPreserveExistingPublicConstructorSemantics() {
        val attack = accepted(fixture(OperationType.ATTACK)) as OperationCombatOrder.Attack
        assertEquals(OperationCombatTactics.HELD_MELEE, attack.tactics)
        assertEquals(OperationBudget(ticks = 600), attack.budget)
        val defendJson = fixture(OperationType.DEFEND)
        defendJson.getAsJsonObject("parameters").addProperty("dutyTicks", 900)
        val defend = accepted(defendJson) as OperationCombatOrder.Defend
        assertEquals(1300, defend.budget.ticks)
        assertEquals(defend.anchor, defend.returnTo)
        val fish = accepted(fixture(OperationType.FISH)) as OperationOrder.Fish
        assertEquals(fish.anchor, fish.returnTo)
        val withNull = fixture(OperationType.FISH)
        withNull.getAsJsonObject("parameters").add("returnTo", JsonNull.INSTANCE)
        assertNull((accepted(withNull) as OperationOrder.Fish).returnTo)
        assertEquals(12000, accepted(fixture(OperationType.FARM)).budget.ticks)
    }

    @Test fun independentTypedValidationRejectsSemanticRelationsBeyondSchema() {
        val transport = fixture(OperationType.TRANSPORT)
        val p = transport.getAsJsonObject("parameters")
        p.add("sources", p.get("destinations").deepCopy())
        rejected(transport)
        val machine = fixture(OperationType.MACHINE)
        machine.getAsJsonObject("parameters").addProperty("noProgressTicks", 10)
        rejected(machine)
        val mining = fixture(OperationType.MINING)
        mining.getAsJsonObject("parameters").addProperty("counting", "CLEARED_VOLUME")
        rejected(mining)
        val farm = fixture(OperationType.FARM)
        farm.getAsJsonObject("parameters").getAsJsonObject("work").addProperty("cycles", 2)
        rejected(farm)
        val defend = fixture(OperationType.DEFEND)
        defend.getAsJsonObject("parameters").add("filter", JsonObject())
        rejected(defend)
        val tactics = fixture(OperationType.ATTACK)
        tactics.getAsJsonObject("parameters").add("tactics", JsonParser.parseString("""{"preference":"RANGED","allowed":"MELEE"}"""))
        rejected(tactics)
    }

    @Test fun everyNumericFieldRejectsOutsideBoundsAndFractionalIntegersWithoutRounding() {
        val catalog = OperationCatalogApi.snapshot()
        var checked = 0
        for (type in OperationType.entries) {
            val normalized = OperationJsonShape.normalize(fixture(type), OperationInput.Reference("orderDocument")).asJsonObject
            fun visit(value: JsonElement, shape: OperationInput) {
                when (shape) {
                    is OperationInput.Reference -> visit(value, catalog.shapes.getValue(shape.name))
                    is OperationInput.Record -> shape.fields.forEach { field -> if (!value.asJsonObject.get(field.name).isJsonNull) visit(value.asJsonObject.get(field.name), field.input) }
                    is OperationInput.Sequence -> value.asJsonArray.forEach { visit(it, shape.item) }
                    is OperationInput.Alternatives -> {
                        val selected = shape.names.single { name ->
                            try { OperationJsonShape.normalize(value, catalog.shapes.getValue(name)); true }
                            catch (_: IllegalArgumentException) { false }
                        }
                        visit(value, catalog.shapes.getValue(selected))
                    }
                    is OperationInput.Numeric -> {
                        for (bad in listOf(shape.minimum - 1, shape.maximum + 1))
                            assertFailsWith<IllegalArgumentException> { OperationJsonShape.normalize(JsonPrimitive(bad), shape) }
                        for (boundary in listOf(shape.minimum, shape.maximum)) OperationJsonShape.normalize(JsonPrimitive(boundary), shape)
                        if (shape.integer) assertFailsWith<IllegalArgumentException> {
                            OperationJsonShape.normalize(JsonParser.parseString(shape.minimum.toLong().toString()+".0000000000000001"), shape)
                        }
                        checked++
                    }
                    else -> Unit
                }
            }
            visit(normalized, OperationInput.Reference("orderDocument"))
        }
        assertTrue(checked > 200, "Checked $checked numeric fields")
    }

    @Test fun malformedDuplicateOversizedAndCodeLikeDocumentsNeverBecomeOrders() {
        val valid = fixture(OperationType.NAVIGATE).toString()
        for (bad in listOf(valid.replaceFirst("{", """{"documentVersion":1,"""),
            valid + " {}", valid.replace("1.0", "NaN"), "[]", "null",
            """{"documentVersion":1,"type":"run_command","definitionVersion":1,"parameters":{}}""")) {
            if (bad != valid) assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(bad), bad)
        }
        assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeOrder(" ".repeat(65537)))
        val fractional = fixture(OperationType.DELIVER)
        fractional.getAsJsonObject("parameters").add("quantity", JsonParser.parseString("1.0000000000000001"))
        rejected(fractional)
    }

    @Test fun eightChangeKindsDecodeWithoutClaimingRuntimeAdmission() {
        val parameters = mapOf(
            "QUANTITY" to """{"amount":10}""", "RECIPIENTS" to """{"containers":{"positions":[{"x":3,"y":64,"z":0}]}}""",
            "SOURCES" to """{"containers":null}""", "EXTEND_TIME" to """{"ticks":100}""",
            "REPLACE" to JsonObject().also { it.add("order", fixture(OperationType.NAVIGATE)) }.toString(),
            "TACTICS" to """{"tactics":{}}""", "REACTION" to """{"policy":{}}""", "LOGISTICS" to """{"policy":{}}""")
        for ((kind, args) in parameters) {
            val doc = JsonObject()
            doc.addProperty("documentVersion", 1); doc.addProperty("type", kind); doc.add("parameters", JsonParser.parseString(args))
            assertIs<OperationDocumentResult.Accepted<OperationChange>>(OperationDocumentApi.decodeChange(doc.toString()), kind)
            doc.getAsJsonObject("parameters").addProperty("unknown", true)
            assertIs<OperationDocumentResult.Rejected>(OperationDocumentApi.decodeChange(doc.toString()), kind)
        }
    }

    @Test fun exportedSchemasAndCatalogAreStableAndContainActualDefaults() {
        val order = OperationDocumentApi.orderSchema()
        assertEquals(order, OperationDocumentApi.orderSchema())
        val schema = JsonParser.parseString(order).asJsonObject
        assertEquals("https://json-schema.org/draft/2020-12/schema", schema.get("$"+"schema").asString)
        assertNotNull(schema.getAsJsonObject("$"+"defs").get("change_QUANTITY"))
        assertEquals(16, JsonParser.parseString(OperationDocumentApi.catalogJson()).asJsonObject.getAsJsonArray("operations").size())
        assertNotNull(JsonParser.parseString(OperationDocumentApi.changeSchema()))

    }
}
