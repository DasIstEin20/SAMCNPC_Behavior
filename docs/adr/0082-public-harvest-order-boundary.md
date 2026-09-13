# ADR 0082: Public harvest order boundary

Status: verified by grouped Y. 2026-09-13.

The remaining five assignment families use the same public gateway: Mining, Farm,
Planting, Food and Lumberjack. Inclusive work boxes/exclusions, literal resources,
mining access permissions and counting basis, tunnel geometry, seed/sapling reserves,
crop cycles, explicit planting bases, food acquisition modes and separate wood supplies
remain bounded immutable inputs. Each converts to its original validator and executor.
No world mutation, crafting, new policy or persistence field is added.

Resource identifiers require bounded namespaced syntax at construction, matching the
existing validated resource filter. Geometry and cross-field permissions are checked by
the established pure task validator. Lists are bounded before copying; nested replanting
is exactly one Planting order and cannot recursively contain another wood task.
New wood assignment uses definition v2; legacy v1 remains a supported manual/save input,
without silently changing its combined-container contract. Public delivery similarly
continues to use v2. All sixteen operation IDs now have typed assignment inputs; the
complete parameter catalog and remaining public amendment variants are separate P11 work.

Six unit groups cover all supported mining directions/modes, crop modes, species/layouts,
food sources, explicit nonplayer hunting, preserved reserves, wood/replant compatibility
and immutable collections. The existing 24 separate-JVM fixtures assigned fifteen
public types; Delivery v2 has its independent W native physical/replay test. All nine
ordinary client job families used public assignment with unchanged physical oracles.

Y: 375 units (45/324/6), 24 separate-JVM checkpoints, 12 client cases and three-mod loading passed. Source b143c64d710a9e0920f738a1d2e5c9c1199fac583e2c0a08e4f21470e3be2682. Native mechanics and lifecycle evidence retain W scope.
