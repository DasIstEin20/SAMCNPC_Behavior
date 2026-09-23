# 0108: Check JAR API identities before dependent initialization

Status: accepted, 2026-09-23. Audit A9.

Submodule pins select build inputs but do not control installed JAR combinations.
An actual installed Forge baseline accepted legacy Core plus current Behavior/LLM
without checking API metadata (installed-a9-baseline-legacy-core.json). A semantic
version shared by development builds is not an API compatibility contract.

Each mod advertises an explicit API version and a SHA-256 build fingerprint in
Forge modproperties. Consumers declare their required API versions there and check
them as their first constructor action using Forge metadata alone. Do not invoke
new dependent API methods to ask if they exist. Missing legacy identifiers and
different identifiers fail with a named consumer/dependency/required/installed
diagnostic before runtime initialization. Disabled LLM still validates its JARs.
Core has no dependency on or knowledge of its consumers.

Build fingerprints cover production source/resources and effective module/root
build scripts and pinned properties. They identify build inputs, not signatures
or JAR byte hashes. Compatible code changes can share an API ID while producing
different fingerprints. A changed public contract requires an explicit ID bump;
release artifact SHA-256 remains separate evidence. Existing three-JAR packaging,
dependency direction and ForgeGradle resource expansion are retained.
