# Project state — 2026-09-20

Operation catalog/document milestone L0 is implemented and verified in the canonical
three-module workspace: sixteen families, eight changes, immutable parameter metadata,
bounded strict JSON decoding and generated schema using the same descriptors.
Authority, task revisions, expiry and existing semantic validators remain mandatory.

Evidence: canonical clean build; Core45 + Behavior337 + LLM6 unit tests;
208 required Behavior GameTests; 12 real client operation cases;
three-mod client/dedicated loading; 276 independent Draft202012 checks.
Source snapshot stayed unchanged across that campaign. Subsequent LLM configuration
campaign also passed all 337 unchanged Behavior unit tests.

Standalone publication clean build, all 337 Behavior unit tests and exportOperationCatalog
passed against pinned Core 74d2ba9. Generated catalog covers sixteen operation families.
P11.1/P11.7 are complete in the canonical plan (legacy104/112, Zoo23/23).
Authenticated two-account skins are human MANUAL_PENDING and do not block autonomous
completion. LLM functionality is a separate project and cannot control Core primitives.
See docs/OPERATION_API.md, docs/OPERATION_CATALOG_AUDIT.md and ADR 0086.
