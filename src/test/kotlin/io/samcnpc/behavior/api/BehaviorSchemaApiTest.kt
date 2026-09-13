package io.samcnpc.behavior.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BehaviorSchemaApiTest {
    private fun bundled() = checkNotNull(javaClass.getResourceAsStream("/samcnpc/behavior-pack.schema.json")).use { it.reader(Charsets.UTF_8).readText() }

    @Test fun generatedCatalogKeepsStructuralContractAndEveryRegisteredId() {
        val original = JsonParser.parseString(bundled()).asJsonObject
        val local = Path.of("contracts/behavior-pack.schema.json")
        val contract = (if (Files.isRegularFile(local)) local else Path.of("../contracts/behavior-pack.schema.json")).toAbsolutePath().normalize()
        assertEquals(original, JsonParser.parseString(Files.readString(contract)))
        val text = BehaviorSchemaApi.registeredSchema()
        assertSame(text, BehaviorSchemaApi.registeredSchema())
        val schema = JsonParser.parseString(text).asJsonObject
        val registeredContract = contract.parent.resolve("behavior-pack-registered.schema.json")
        assertEquals(schema, JsonParser.parseString(Files.readString(registeredContract)))
        val example = contract.parent.parent.resolve("docs/examples/behavior-packs/follow.json")
        val report = BehaviorPackValidationApi.validateCandidate(Files.readString(example), "author:follow")
        assertTrue(report.accepted, report.messages.joinToString())
        assertEquals(original.get("properties"), schema.get("properties"))
        val catalog = BehaviorCatalogApi.snapshot()
        for ((key, field, rows) in listOf(Triple("test", "condition", catalog.conditions), Triple("action", "action", catalog.actions))) {
            val entries = schema.getAsJsonObject("\$defs").getAsJsonObject(key).getAsJsonArray("oneOf")
            val ids = entries.map { it.asJsonObject.getAsJsonObject("properties").getAsJsonObject(field).get("const").asString }
            assertEquals(rows.map { it.id }, ids)
        }
        assertTrue(text.contains("x-samcnpc-greater-than") && text.contains("x-samcnpc-default-from"))
    }

    @Test fun sharedCorpusUsesActualRuntimeValidationAndExportsIndependentInputs() {
        val corpusText = checkNotNull(javaClass.getResourceAsStream("/samcnpc/schema-corpus.json")).use { it.reader(Charsets.UTF_8).readText() }
        val corpus = JsonParser.parseString(corpusText).asJsonArray
        assertTrue(corpus.size() >= 50)
        val results = JsonArray()
        for (row in corpus) {
            val entry = row.asJsonObject
            val name = entry.get("name").asString
            val result = BehaviorPackValidationApi.validateCandidate(entry.get("document").asString, "corpus:" + name)
            assertEquals(entry.get("runtime").asBoolean, result.accepted, name + ": " + result.messages)
            if (!result.accepted) assertTrue(result.messages.all { it.startsWith("corpus:" + name) })
            val output = JsonObject()
            output.addProperty("name", name); output.addProperty("accepted", result.accepted)
            val messages = JsonArray(); result.messages.forEach(messages::add); output.add("messages", messages)
            results.add(output)
        }
        val directory = Path.of("build/reports/behavior-schema"); Files.createDirectories(directory)
        Files.writeString(directory.resolve("structural.schema.json"), bundled())
        Files.writeString(directory.resolve("registered.schema.json"), BehaviorSchemaApi.registeredSchema())
        Files.writeString(directory.resolve("corpus.json"), corpusText)
        Files.writeString(directory.resolve("runtime-results.json"), results.toString())
    }

    @Test fun shippedPacksPassTheSamePublicGateway() {
        val directory = Path.of("src/main/resources/data/samcnpc_behavior/behaviors")
        val paths = Files.list(directory).use { stream -> stream.filter { it.fileName.toString().endsWith(".json") }.sorted().toList() }
        assertTrue(paths.size >= 14)
        for (path in paths) {
            val result = BehaviorPackValidationApi.validateCandidate(Files.readString(path), path.fileName.toString())
            assertTrue(result.accepted, result.messages.joinToString())
        }
    }
}
