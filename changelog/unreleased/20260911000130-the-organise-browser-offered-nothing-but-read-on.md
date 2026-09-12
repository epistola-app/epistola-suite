---
type: fix
scopes: [catalogs]
audience: user
title: The organise browser offered nothing but read-only resources.
---

Every row was disabled: the listing sent `catalogType` as `AUTHORED` while the page compared it against `authored`, so the check was always true and no resource could be selected. Read-only catalogs are now excluded server-side instead of being listed and disabled, which removes the comparison altogether rather than correcting it.
