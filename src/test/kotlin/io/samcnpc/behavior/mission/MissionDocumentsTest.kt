package io.samcnpc.behavior.mission

import com.google.gson.JsonParser
import io.samcnpc.behavior.api.MissionApi
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class MissionDocumentsTest {
    private fun fixture() = JsonParser.parseString("""{
      "documentType":"samcnpc:mission","documentVersion":1,"id":"acceptance:tutorial","dimensionId":"minecraft:overworld",
      "requirements":[{"id":"stock","predicate":{"type":"inventory_count","query":"minecraft:coal","count":2,"minimumDurability":0}}],
      "stages":[{"id":"check","pack":"acceptance:check","completion":["stock"],"timeoutTicks":200,"retries":0,"success":null}]
    }""").asJsonObject
    @Test fun validDocumentSeparatesCurrentStockFromStages() {
        val parsed=MissionDocuments.read(fixture().toString())
        assertEquals(2,assertIs<MissionPredicate.Inventory>(parsed.requirements.single().predicate).count)
        assertNull(parsed.stages.single().order)
        assertFailsWith<UnsupportedOperationException> { (parsed.stages as MutableList).clear() }
    }
    @Test fun duplicateMissingAndCyclicReferencesAreRejected() {
        val base=fixture()
        val cases=listOf<(com.google.gson.JsonObject)->Unit>(
            { it.getAsJsonArray("stages").add(it.getAsJsonArray("stages")[0].deepCopy()) },
            { it.getAsJsonArray("stages")[0].asJsonObject.addProperty("success","missing") },
            { it.getAsJsonArray("stages")[0].asJsonObject.addProperty("success","check") },
            { it.getAsJsonArray("stages")[0].asJsonObject.getAsJsonArray("completion").set(0,com.google.gson.JsonPrimitive("absent")) },
            { it.getAsJsonArray("requirements").add(it.getAsJsonArray("requirements")[0].deepCopy()) },
            { val stage=it.getAsJsonArray("stages")[0].deepCopy().asJsonObject; stage.addProperty("id","unreachable"); it.getAsJsonArray("stages").add(stage) },
        )
        for (change in cases) { val value=base.deepCopy(); change(value); assertFalse(MissionApi.validate(value.toString()).accepted) }
    }
    @Test fun unsupportedActionsPredicatesRetryAndUnboundedInputFailClosed() {
        for ((key,value) in listOf("timeoutTicks" to 72001,"retries" to 1)) {
            val doc=fixture();doc.getAsJsonArray("stages")[0].asJsonObject.addProperty(key,value)
            assertFalse(MissionApi.validate(doc.toString()).accepted)
        }
        val doc=fixture();doc.getAsJsonArray("requirements")[0].asJsonObject.getAsJsonObject("predicate").addProperty("type","execute_command")
        assertFalse(MissionApi.validate(doc.toString()).accepted)
        assertFalse(MissionApi.validate("{"+" ".repeat(131073)+"}").accepted)
        assertFalse(MissionApi.validate(fixture().toString().replace("\"documentVersion\":1","\"documentVersion\":1,\"documentVersion\":1")).accepted)
    }
    @Test fun currentStockCannotBeDisguisedAsLatchedHistoricalSuccess() {
        val doc=fixture();doc.getAsJsonArray("requirements")[0].asJsonObject.addProperty("latched",true)
        assertFalse(MissionApi.validate(doc.toString()).accepted)
        val task=fixture();task.getAsJsonArray("requirements")[0].asJsonObject.add("predicate",JsonParser.parseString("""{"type":"task_success","stageId":"check"}"""))
        assertFalse(MissionApi.validate(task.toString()).accepted)
    }
    @Test fun persistenceRetainsBudgetPauseAndExactIdentityWithoutPretendingReconciliation() {
        val definition=MissionDocuments.read(fixture().toString())
        val record=MissionRecord(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),definition,mapOf("acceptance:check" to "a".repeat(64)),emptyList())
        record.remaining=83;record.state=MissionState.PAUSED
        val restored=MissionStore.read(MissionStore.write(record))
        assertEquals(record.id,restored.id);assertEquals(83,restored.remaining)
        assertEquals(MissionState.PAUSED,restored.state);assertTrue(restored.restored)
        assertEquals(record.definition.hash,restored.definition.hash)
    }
    @Test fun corruptOrFutureStoreIsPreservedAndCannotAdmitNewMissions() {
        val tag=CompoundTag();tag.putInt("version",999);tag.putString("futureData","preserve")
        val store=MissionStore.load(tag)
        assertNotNull(store.problem);assertFalse(store.canAdd(UUID.randomUUID()))
        assertEquals(tag,store.save(CompoundTag()))
    }
    @Test fun forgedCompletionAndFutureReceiptsAreRejected() {
        val definition=MissionDocuments.read(fixture().toString())
        val r=MissionRecord(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),definition,mapOf("acceptance:check" to "b".repeat(64)),emptyList())
        val fake=MissionStore.write(r);fake.putString("state","COMPLETED")
        assertFailsWith<IllegalArgumentException> { MissionStore.read(fake) }
        val forged=MissionStore.write(r);forged.getCompound("confirmed").putUUID("check",UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> { MissionStore.read(forged) }
    }
    @Test fun missionManifestMustExactlyListArchiveDocuments() {
        val manifest="""{"documentType":"samcnpc:mission_bundle","documentVersion":1,"packs":["behaviors/check.json"],"missions":["missions/check.json"]}"""
        val entries=listOf("external-zip:x.zip!/mission-manifest.json" to manifest,"external-zip:x.zip!/behaviors/check.json" to "{}","external-zip:x.zip!/missions/check.json" to fixture().toString())
        MissionBundle.validate(entries)
        assertFailsWith<IllegalArgumentException> { MissionBundle.validate(entries.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { MissionBundle.validate(entries.drop(1)) }
    }
}
