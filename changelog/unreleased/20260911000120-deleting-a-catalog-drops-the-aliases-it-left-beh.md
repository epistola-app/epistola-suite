---
type: fix
scopes: [catalogs]
audience: user
title: Deleting a catalog drops the aliases it left behind.
---

Aliases at the addresses of resources moved out of a catalog have no foreign key on it, so they outlived its deletion and reserved those addresses for a catalog registered later under the same key, with no page to release them from. `UnregisterCatalog` removes them with the catalog; published references to those addresses stop resolving from then on.
