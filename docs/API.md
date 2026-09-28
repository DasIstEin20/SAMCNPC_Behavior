# Public Behavior APIs

Public Kotlin interfaces are under `src/main/kotlin/io/samcnpc/behavior/api/`.
Core is the only required first-party dependency. Use server-thread APIs with the
actual authorized player; do not access internal task stores to create work.

- `BehaviorCatalogApi` / `BehaviorSchemaApi`: registered component IDs, parameters,
  channels and generated schema. Pack document version is 1; catalog version is 2.
- `OperationCatalogApi` / `OperationDocumentApi`: supported typed operation families
  and strict definition parsing. The operation catalog version is 5.
- `OperationSupervisionApi`: bounded observations, compare-and-set assignment,
  amendment and controls with task identity, revisions, authority and freshness.
- `MissionApi`: validate, list and start finite missions; status/pause/resume/cancel.

Generated contracts are in `contracts/`. A rule's actions are candidates for
deterministic channel arbitration, not sequential instructions. `run_*_task`
advances existing durable work; it does not construct an operation. Conditions are
read-only. Data cannot execute scripts, arbitrary commands or class names.

See [local preparation](LOCAL_AUTONOMY.md), [external ZIPs](EXTERNAL_BEHAVIOR_ZIPS.md)
and [missions](MISSIONS.md). The [follow example](examples/behavior-packs/follow.json)
is ordinary authoring data. Invalid reload candidates preserve the accepted registry.
