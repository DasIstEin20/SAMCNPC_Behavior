# ADR 0081: Public combat and inventory orders

Status: verified by grouped X. 2026-09-13.

Extend the same public assignment boundary with Attack v2, Defend v1, AreaAttack v1,
Patrol v1 and InventoryWork v1. They retain their existing operation IDs and executors.
Typed tactics separate preferences from hard weapon constraints; explicit target/filter,
health thresholds, distance, duty, patrol reaction and return parameters pass through the
same existing validators. Attack preserves the existing held-item melee default.

Inventory work exposes finite Supply, Unload and Pickup requests with retained/source
reserves, collection limits and a separate physical return budget. Public lists are
bounded before copying and cannot be changed after handoff. The gateway retains W's
current-actor permission gate, finite request lifetime and exact prior-task comparison.
No new world primitive, executor, command dispatch, provider, persistence field or JSON
behavior action is introduced. The remaining five harvest families and full parameter
catalog stay open; this is eleven public assignment types, not full P11 completion.

Existing 24 checkpoint scenes will assign ten public types through the gateway, compare
exact definitions with their original manual fixtures, persist original requests and
reject replay after separate-JVM loading. Actual client cases will exercise public
Transport, Inventory, Patrol and Defend with the original physical/permission controls.
Four focused unit groups cover tactical contradictions, patrol relationships, inventory
reserve/return constraints and immutable bounded lists; the independent LLM consumer
compiles only against published types. Existing world executors remain covered by W.

X: 368 units,24 checkpoints across ten public families in distinct JVMs,12 client cases including four public assignments,three-mod loading and distribution passed. Exact source/evidence: p11-orders-x-evidence.json.
