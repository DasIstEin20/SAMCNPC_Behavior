# Behavior document contracts

`behavior-pack.schema.json` describes the generic version-1 shape.
`behavior-pack-registered.schema.json` adds the registered conditions/actions,
argument names, types, bounds and channels. Runtime semantic validation remains
authoritative for identity, duplicate policy, references and bounded execution.
Examples are authoring documents, not executable scripts.

The operation schemas and catalog are generated from the same public descriptors
used by runtime admission. Generate them with `exportOperationCatalog` in the
Behavior project. Mission documents use their separate versioned bundle format;
they are not valid rule-pack JSON.
