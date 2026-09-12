---
type: feat
scopes: [catalogs]
audience: user
title: Variant attributes can be moved between catalogs.
---

The first type re-keyed onto its stable identity, so relocation is a plain column update. Every reference to an attribute is a key in a variant's attribute map, which the move rewrites — nothing is left resolving through an alias. Alpha, behind the `resource-relocation` toggle.
