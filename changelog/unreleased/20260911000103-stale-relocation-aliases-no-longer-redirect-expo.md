---
type: fix
scopes: [catalogs]
audience: user
title: Stale relocation aliases no longer redirect exports.
---

A resource re-created at an address a moved resource left behind now wins over that alias everywhere, so exporting the source catalog keeps the new resource's own references instead of rewriting them to the moved resource. Moving a resource back to a catalog it previously occupied is also supported, and a relocation plan is only invalidated by edits that actually change the move.
