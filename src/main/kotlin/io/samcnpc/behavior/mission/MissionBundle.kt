package io.samcnpc.behavior.mission

import io.samcnpc.behavior.registry.BoundedBehaviorJson

/** A mission bundle is indexed inside the existing bounded, non-extracting ZIP reader. */
internal object MissionBundle {
    fun validate(documents: List<Pair<String,String>>) {
        val missions = documents.filter { it.first.substringAfter("!/").startsWith("missions/") }
        val manifest = documents.singleOrNull { it.first.endsWith("!/mission-manifest.json") }
        if (missions.isEmpty() && manifest == null) return
        require(manifest != null && missions.size in 1..32) { "mission bundle needs manifest and 1..32 missions" }
        val root = MissionDocuments.obj(BoundedBehaviorJson.parse(manifest.second), setOf("documentType","documentVersion","packs","missions"))
        require(MissionDocuments.text(root,"documentType") == "samcnpc:mission_bundle")
        MissionDocuments.integer(root,"documentVersion",1,1)
        for ((key,prefix) in listOf("packs" to "behaviors/", "missions" to "missions/")) {
            val paths = MissionDocuments.array(root,key,1,64).map(MissionDocuments::string)
            require(paths.distinct().size == paths.size) { "duplicate manifest path" }
            val actual = documents.map { it.first.substringAfter("!/") }.filter { it.startsWith(prefix) }.sorted()
            require(paths.sorted() == actual) { "manifest $key differs from archive documents" }
        }
    }
}
