# Writing a behavior pack

Use [follow.json](examples/behavior-packs/follow.json) as a small complete example.
It uses registered conditions/actions, ordinary movement speed 1 and a horizontal stopping
distance of 2 blocks. The optional start distance defaults to stopping distance plus 2.

Copy only that file into your server's `config/samcnpc/behaviors/` directory. As an operator,
reload, then assign the pack to an idle NPC you may control:

```text
/samcnpc behavior reload
/samcnpc behavior packs
/samcnpc behavior assign <npc-name-or-UUID> example:follow
/samcnpc behavior diagnostics <npc-name-or-UUID>
```

Reload validates the entire candidate registry before activation. One malformed file,
duplicate ID, inaccessible file or invalid parameter rejects the candidate and keeps the
last valid registry and current work. A successful reload releases transient controls and
reobserves. Removing a pack still assigned to an NPC produces safe idle plus a diagnostic.

To edit safely, write a complete file with a non-JSON temporary suffix, replace the final
JSON, then reload. Filenames are distinct from the namespaced pack ID. Do not duplicate a
built-in ID: there is no implicit override. Keep required action channels in the pack's
channel list. Pack priority and rule priority arbitrate eligible intents deterministically.

## Validation and editor schemas

[Structural schema](../contracts/behavior-pack.schema.json) describes document v1.
[Registered-component schema](../contracts/behavior-pack-registered.schema.json) additionally
describes known action/condition IDs and their argument fields/types/bounds/defaults.
The latter is generated from `BehaviorCatalogApi.snapshot()` and exposed as cached text
by `BehaviorSchemaApi.registeredSchema()`. It is useful for editor completion.

Use `BehaviorPackValidationApi.validateCandidate(json, source)` for authoritative
acceptance. Related-field inequalities, action-channel compatibility, duplicate rule IDs,
condition depth, byte/tree/file bounds and live reload/world context still require that
compiler/loader. A green schema check alone does not activate or authorize a pack.
See [input limits](JSON_INPUT.md).

The document format and component semantics stay at 1. TaskStore v9 is a separate runtime
persistence format, with explicit older-save migration; do not put it into `schemaVersion`.

## Rejected examples

Keep the [rejected examples](examples/behavior-packs/rejected/) outside the active config
directory. Their [captured diagnostics](examples/behavior-packs/rejected/expected-diagnostics.json)
come from the same public validator; the source label is `corpus:<case>` in these tests
and changes to the actual external filename on disk.

| Example | Why it is rejected |
| --- | --- |
| `unknown-action.json` | The action ID is absent from the registry. |
| `speed-too-small.json` | Speed is below 0.1. |
| `invalid-distance-relation.json` | Start distance is smaller than stop distance, despite both satisfying separate numeric ranges. |
| `missing-action-channel.json` | Follow requests a look channel that the pack did not declare. |

The examples match values in the 54-document runtime/independent-schema corpus. Existing
native reload campaigns prove LKG retention, action rebuild, missing-pack idle and task
completion after restoration. Campaign T additionally loaded this follow example from
disk: the same NPC stopped 7.97 blocks from its summoner with stopDistance=8, held
its position for 20 ticks, then approached to 1.93 blocks after editing to 2 and reloading.
The assignment and NPC identity stayed unchanged. This is native physical movement;
its connected-player fixture is not evidence of authenticated skin resolution.


## Version compatibility

The behavior document and component definition semantics are both version 1; this
release does not reinterpret an existing registered ID. Existing valid version-1 packs
remain valid. Unknown versions are rejected before candidate activation. There is no
older supported behavior-document format requiring a speculative converter.
The catalog representation has its own version 1. TaskStore persistence is independently
versioned at 9, with explicit legacy migrations and preserved invalid data on failure;
it is not an external pack format. Runtime-only semantic checks remain authoritative.
