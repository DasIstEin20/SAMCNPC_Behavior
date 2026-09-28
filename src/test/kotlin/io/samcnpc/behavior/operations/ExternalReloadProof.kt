package io.samcnpc.behavior.operations

import io.samcnpc.behavior.runtime.BehaviorRuntimeService
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deliberately invalid external files exercise the real combined reload transaction. */
internal object ExternalReloadProof {
    fun run(): List<Map<String,Any?>> {
        val before=BehaviorRuntimeService.activePackIds()
        val missions=BehaviorRuntimeService.missionIds()
        val fingerprints=before.associateWith { BehaviorRuntimeService.packFingerprint(it) }
        val rows=mutableListOf<Map<String,Any?>>()
        val root=BehaviorRuntimeService.externalDirectory()
        val loose=root.resolve("acceptance_invalid.json")
        val zip=BehaviorRuntimeService.externalZipDirectory().resolve("acceptance_invalid.zip")
        check(!Files.exists(loose) && !Files.exists(zip))
        val base="""{"schemaVersion":1,"id":"acceptance:negative","description":"negative fixture","priority":1,"channels":["movement"],"rules":[{"id":"move","priority":1,"when":{"test":{"condition":"samcnpc:always"}},"actions":[{"action":"samcnpc:stop_movement"}]}]}"""
        fun reject(name: String) {
            val report=BehaviorRuntimeService.reload()
            check(!report.accepted) { "$name unexpectedly accepted" }
            check(before==BehaviorRuntimeService.activePackIds() && missions==BehaviorRuntimeService.missionIds())
            check(fingerprints.all { (id,hash) -> BehaviorRuntimeService.packFingerprint(id)==hash })
            rows.add(mapOf("case" to name,"result" to "PASS_REJECTED_LKG_RETAINED","diagnostics" to report.messages))
        }
        try {
            for ((name,body) in listOf("malformed_json" to "{", "unknown_component" to base.replace("samcnpc:always","acceptance:unknown"),
                "duplicate_id" to base.replace("acceptance:negative","acceptance:tutorial"),"oversized_json" to " ".repeat(131073),
                "mission_in_rule_directory" to """{"documentType":"samcnpc:mission","documentVersion":1}""")) {
                Files.writeString(loose,body);reject(name);Files.delete(loose)
            }
            for ((name,member,body) in listOf(Triple("traversal_zip","behaviors/../evil.json",base),
                Triple("absolute_zip","/behaviors/evil.json",base),Triple("oversized_zip_member","behaviors/large.json"," ".repeat(131073)),
                Triple("malformed_manifest","mission-manifest.json","{"))) {
                ZipOutputStream(Files.newOutputStream(zip)).use { out ->
                    out.putNextEntry(ZipEntry("behaviors/valid.json"));out.write(base.toByteArray());out.closeEntry()
                    out.putNextEntry(ZipEntry(member));out.write(body.toByteArray());out.closeEntry()
                }
                reject(name);Files.delete(zip)
            }
            check(BehaviorRuntimeService.reload().accepted)
        } finally { Files.deleteIfExists(loose);Files.deleteIfExists(zip) }
        return rows
    }
}
