# ADR 0078: Public amendments and generated component schema

Status: accepted and verified in campaign S, 2026-09-13.

Add four typed amendment variants (quantity total/add, recipients, supply sources, time)
to OperationSupervisionApi using the same current-actor access gate as controls and the
existing TaskAmendments request/replay/revision/expiry/safe-boundary machinery. Container
allow-lists are copied and bounded to 1..8 distinct positions before crossing integration
threads. Unsupported operation/variant combinations return the existing explicit rejection.

OperationReply adds an optional immutable receipt. It describes the actual stored/pending
request only when its full payload and actor match. Conflicting request-ID reuse never
returns an older successful receipt for a different payload. APPLIED/PENDING/REJECTED/
EXPIRED are distinguished; a successful transport of a historical receipt does not imply
the amendment itself was applied. No new persistence format or executor is introduced.

Expose a cached registered-component schema generated from the bundled structural schema
and the real immutable catalog. Known condition/action IDs and required/optional argument
types, enum values, fixed bounds/defaults come from that catalog, whose validator conformance
is tested. Related-field inequalities/defaults are explicit non-executable annotations.
Action-channel compatibility, rule-ID uniqueness, depth/input/file limits and world state
remain checks of the authoritative runtime compiler/loader; schema success is not activation.
Document v1 and definition semantics v1 remain unchanged; TaskStore persistence is v9.

Generation reads the bounded bundled resource once, outside all NPC tick handlers, and uses
the already present Gson dependency. It does not fetch schema URLs. Independent verification
uses isolated Python jsonschema4.26.0 wheels pinned by version/hash, not a mod dependency.
Sources for that development tool: https://pypi.org/project/jsonschema/4.26.0/ and
https://python-jsonschema.readthedocs.io/en/stable/validate/.

Validation must retain physical cargo/pending-cleanup/replay assertions, check typed receipt
identity and actual operator/client use, and run the same corpus through the runtime gateway
and independent Draft202012 schema validation. Assignment/full operation parameter catalog
and unsupported replacement/tactics/logistics API variants remain separate pending work.

Evidence: autonomy/run-20260912-zoo/p11-amend-schema-s-evidence.json and
p11-amend-schema-s-independent.json. Clean build/static/distribution, 355 units,
197 native GameTests, 18 lifecycle scenarios, 12 actual client cases (nine public
control/amendment families), and both three-mod loaders passed. The independent
Draft202012 validator checked the exact 54-document runtime corpus and 16 builtins.
TaskStore v9 separate-process persistence is covered by the preceding R3 campaign;
S did not change its encoding. Full assignment/operation catalog remains pending.
