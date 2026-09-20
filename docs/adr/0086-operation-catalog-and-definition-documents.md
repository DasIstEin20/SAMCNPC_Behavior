# ADR 0086: immutable operation catalog and bounded definition documents

Status: accepted; grouped clean/runtime verification passed, 2026-09-20.

## Context

F02 needs a discoverable contract for the sixteen existing operation families and
eight amendment variants. The component catalog describes behavior-pack actions
and conditions, not operation orders. Public Kotlin orders already share the
deterministic task validators and the connected summoner/operator gateway.

## Decision

OperationCatalogApi exposes cached, deeply immutable records for parameters,
units, defaults, finite variants and named semantic relations. Numeric bounds,
required/nullability rules and variants also drive the structural JSON decoder
and Draft 2020-12 export. Relations are descriptive data, never expressions.

OperationDocumentApi exports the catalog and two schemas and decodes finite
definition documents. Version 1 uses documentVersion, type, definitionVersion
and parameters for orders, and documentVersion, type and parameters for changes.
The operation definition version remains the current version in OperationType;
there is no silent upgrade of old JSON. Existing typed APIs and task persistence
formats are unchanged.

Explicit Kotlin constructors map normalized JSON into existing public orders and
changes. No reflective deserialization, class names, code or command dispatch is
introduced. The existing bounded disk-pack JSON reader rejects duplicates,
malformed Unicode, excessive depth/nodes and oversized numeric tokens; the new
document boundary additionally caps UTF-8 input at 64 KiB. Unknown fields, invalid
types, nonintegral integers, unsupported IDs/versions and nonfinite values reject.

Existing TaskPublicOrders/TaskChanges provide semantic validation. A successful
decode returns data only. Assignment and amendment still check the live actor,
NPC, dimension/range, permissions, task/revisions, game-time expiry, receipts,
safe boundary and world through OperationSupervisionApi. Decoding cannot assign
work or claim a successful world effect. There are no filesystem loaders, new
packet routes, provider requests or per-tick parsers in this change.

## Consequences

The same contract supports author tools and the later LLM module without a second
executor or fourth mod. Generated schema annotations expose cross-field relations,
but a generic schema validator cannot establish world facts or admission rights.
Defaults derived from another field are explicit x-defaultFrom data and are
resolved at the bounded document boundary, not interpreted as arbitrary code.

The JSON coordinate format deliberately uses the supported global coordinate
envelope everywhere. Some typed constructors defer stricter contextual checks
to their existing validators; a structurally valid document may still reject.
Operation-specific amendment discovery is a candidate set: for example stored
food alone accepts source edits, mission goals remain fixed, and exhausted
history or an active interruption can prohibit a currently advertised change.

No new dependency or persistent format is needed. Build-time export lives in
test sources. Independent schema checks, typed unit/variant cases and real
server/client admission/effect checks provide distinct evidence; their actual
results are recorded in PROJECT_STATE.md and the run evidence.
