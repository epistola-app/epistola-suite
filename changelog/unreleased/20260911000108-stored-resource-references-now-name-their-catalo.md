---
type: feat
scopes: [catalogs]
audience: dev
title: Stored resource references now name their catalog.
---

Saving or publishing fills in the containing catalog on relative references, so a published reference keeps its meaning when its owner is relocated. Exports still travel relative to their own catalog, so a catalog remains installable under a different key. Assets stay unqualified: they resolve tenant-globally.
