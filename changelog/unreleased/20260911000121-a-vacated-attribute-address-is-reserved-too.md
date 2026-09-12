---
type: fix
scopes: [catalogs]
audience: user
title: A vacated attribute address is reserved too.
---

`CreateAttributeDefinition` now calls the shared address reservation, as stencil and template creation already did, so the planner's "retained alias occupies the target" rule and the create path agree for every movable type. A test moves one resource of each movable type and expects the replacement to be refused; registering a new type without wiring the guard fails it.
