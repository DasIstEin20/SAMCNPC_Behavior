# External JSON input

Place complete UTF-8 files under the server's configured `config/samcnpc/behaviors/`
directory. An operator can run `/samcnpc behavior reload`, then a summoner/operator can
assign an active pack with `/samcnpc behavior assign <npc> example:look`.

A minimal pack:

```json
{
  "schemaVersion": 1,
  "id": "example:look",
  "description": "Look toward the summoner.",
  "priority": 0,
  "channels": ["look"],
  "rules": [{
    "id": "look",
    "priority": 0,
    "when": {"test": {"condition": "samcnpc:has_summoner"}},
    "actions": [{"action": "samcnpc:look_at_summoner"}]
  }]
}
```

The [structural v1 schema](../contracts/behavior-pack.schema.json) describes the document.
The runtime additionally resolves registered IDs, validates their arguments, checks channel
requirements, and limits condition depth. Built-ins, disk files and the public
`BehaviorPackValidationApi.validateCandidate` gateway share the same compiler.

| Boundary | Limit |
| --- | --- |
| JSON files / immediate directory entries | 64 / 1024 |
| Actual UTF-8 input | 128 KiB |
| JSON values / nesting below root | 16384 / 32 |
| String / numeric token | 512 Unicode code points / 64 characters |
| Rules / actions per rule / condition children | 256 / 16 / 16 |
| Condition depth | 8 |

Each registered definition has its own allowed arguments and bounds. Numerical integers
are exact: `1.0` and `1e0` are valid integers; `1.0000000000000001` is not.
Valid supplementary Unicode characters count once. Invalid UTF-8, unpaired surrogates,
comments, raw string controls and duplicate decoded object keys are rejected.

There is no implicit override: duplicate pack IDs or rule IDs reject the candidate.
A malformed, unreadable, locked, oversized or observably changed file rejects the entire
reload. The prior registry and active NPC task/action remain intact. A successful reload
rebuilds action handles without replenishing the original task budget. An assigned pack
that disappears causes safe idle with a diagnostic until it is restored or reassigned.

The `samcnpc` and `behaviors` directories must be real directories below the configured
root. Junction/symlink redirects and non-regular JSON entries are rejected; reads do not
follow file links. Save to a temporary non-JSON name, replace the complete target, then
reload. Before/after file metadata checks do not create an OS-wide filesystem transaction.

Diagnostics identify bounded source/rule/field context. For example, adding an unknown
`script` property reports that property, and an unregistered action reports its ID and
rule/action position. JSON cannot contain executable scripts, classes or commands.

See [the input decision](adr/0075-bounded-behavior-json-input.md) and [validation scope](VALIDATION.md).
The complete operation catalog and typed assign/amend/control API are still being finalized.
