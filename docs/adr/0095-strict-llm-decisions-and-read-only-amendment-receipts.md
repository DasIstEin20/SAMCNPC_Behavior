# ADR 0095: Strict LLM decisions and read-only amendment receipts

Date: 2026-09-20
Status: Accepted; grouped clean/native/client verification passed

An LLM answer may arrive after a manual stop, an authority change or a task
replacement. An HTTP success also says nothing about whether a subsequent task
mutation was applied. Existing amendment submission can recognize a replay, but
using it as a receipt query can submit a request that never arrived originally.

Use a closed, versioned eight-decision document and the published Behavior operation
decoder/catalog. Strict local parsing, typed policy and fresh server authorization
precede the existing public assignment/amendment/control gateways. Non-mutating
decisions cannot enter those gateways. Preserve original context IDs, task revisions
and deadlines; never repair a stale request by changing its preconditions.

Add OperationSupervisionApi.amendmentReceipt as an authorized read-only lookup for
the exact original payload. It returns NOT_FOUND without applying anything when no
matching receipt exists. It reuses the existing persisted Behavior receipt matching
logic and does not introduce another task store or alter the persistence format.

Keep one transient admission slot per goal/NPC. Mark it consumed before validation
and uncertain before dispatch. Reconciliation never repeats an uncertain mutation.
Amendment outcomes use actual exact receipts; current assignment/control state is
only an observation. Unresolved PENDING/UNCERTAIN state prevents automatic rebinding.
The later goal store must persist the minimal recovery intent and resume through
fresh observation, without persisting HTTP futures or claiming exactly-once effects.

Model text, summaries, item names and goals remain untrusted data. Schema and policy
come from server code/config. Explicit JSON_OBJECT must receive the schema in its
system message as JSON_SCHEMA already does through response_format. The complete
serialized request remains bounded to 64 KiB, including JSON escaping.

The dependency direction and Kotlin/Java 17 toolchain stay unchanged. No Core world
mutation API is exposed to model output, and no production runtime dependency is added.

Validation: strict decoder/policy/receipt units, independent Draft 2020-12 schema
checks, real dedicated-server authorization/replay/lost-reply probes and full-context
HTTP in both explicit formats. Existing native/client work scenarios exercise
read-only missing, pending, expired and applied receipt queries.

Final evidence: 449 units, 171 independent schema checks, 214 native Behavior cases,
12 actual client cases (9 receipt consumers), 30 dedicated admission probes and
two full-context HTTP decisions. All 774 frozen source/build hashes match.
