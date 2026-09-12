---
type: refactor
scopes: [catalogs]
audience: dev
title: Relocation is driven by a per-type descriptor.
---

`MovableResource` declares which table a move updates and which content reference kinds target it, replacing four hardcoded stencil checks. A type appears there only once its table is keyed by identity, so the unsupported-type blocker is derived rather than maintained by hand.
