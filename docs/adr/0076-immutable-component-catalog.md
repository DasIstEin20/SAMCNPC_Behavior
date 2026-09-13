# ADR 0076: Immutable public component catalog

Status: accepted; verified in P11 Q. 2026-09-13.

P11 clients need the actual condition/action vocabulary without importing runtime handlers
or guessing numeric ranges. Expose BehaviorCatalogApi.snapshot with immutable descriptors
for every registered condition and action. Channels come from the runtime definition;
parameters, required fields, enum choices, numeric bounds and defaults are explicit data.
There is no execution, assignment, world access or mutable JSON/handler in the result.

Catalog format version, accepted behavior document version and definition semantics version
are separate fields. Their initial value is 1. Existing IDs keep their meanings; the bounded
input correction restores the documented v1 integer/Unicode rules. A future semantic change
needs an explicit new definition version/ID and migration decision, not a silent catalog edit.
This component catalog does not version or mutate TaskStore v8 persistence.

One numeric default is relative to another parameter: follow startDistance defaults to
stopDistance plus 2 and must exceed stopDistance. Represent that relationship as typed data.
It does not create an expression interpreter. Static metadata is cached once. A new registry
ID requires an explicit parameter or no-argument entry, checked against the real registry.

Verification must compare every descriptor with registered argument validation, including
required/optional fields, type, range, enum, exact integers and default relationships, and
prove clients cannot mutate catalog collections. A consumer must use only behavior.api.
Schema boundary/conformance cases accompany this P11 slice. Operation parameters and
typed assign/amend/control/status are separate work; this catalog does not pretend to
expose them or to replace the existing deterministic executor.

Q passed clean build, static/distribution checks, 343 units (45 Core/296 Behavior/2 LLM)
and actual three-mod client and dedicated-server loading. Both loading reports confirm
14 conditions/23 actions through the public catalog. Three catalog groups compare all
argument contracts and immutable collections; three schema groups cover rule/action/ID/
expression limits and malformed documents. Exact 1024/1025 directory-entry boundary is
covered alongside the existing 64/65 JSON-file boundary. Private evidence and source hash:
autonomy/run-20260912-zoo/p11-catalog-q-evidence.json. This is selected conformance coverage,
not a claim that an independent full JSON Schema implementation was used.
