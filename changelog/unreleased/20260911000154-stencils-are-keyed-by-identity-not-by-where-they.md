---
type: refactor
scopes: [catalogs]
audience: dev
title: Stencils are keyed by identity, not by where they live.
---

A stencil version carried a copy of its parent's address alongside a pointer to the parent — the same fact stated twice, with nothing forcing the two to agree, and roughly ten queries filtering on the copy. Versions name the stencil now, and read the address back through it, so a move updates one row. The two interim migrations that added and then patched that copy are gone, replaced by one that ships the final shape.
