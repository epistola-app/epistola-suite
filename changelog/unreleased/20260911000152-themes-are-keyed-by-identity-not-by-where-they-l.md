---
type: refactor
scopes: [catalogs]
audience: dev
title: Themes are keyed by identity, not by where they live.
---

A template's own theme binding and the tenant-wide default now name the theme itself rather than a copy of its address, so moving or renaming a theme updates a single row. Both were previously among the six foreign keys relocation weakened to `ON UPDATE CASCADE`; neither needs one now. Queries that report the address read it from `themes`, leaving the REST, export and UI shapes unchanged, and a binding to an address that resolves to nothing is refused by the foreign key rather than silently stored as "no theme" (which would have fallen the template back to the tenant default).
