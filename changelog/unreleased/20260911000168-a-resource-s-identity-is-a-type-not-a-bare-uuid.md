---
type: refactor
scopes: [catalog]
audience: dev
title: A resource's identity is a type, not a bare `UUID`.
---

`ResourceIdentity` replaces `java.util.UUID` everywhere a catalog resource's stable identity is carried — the relocation planner, the identity registry, the resource graph, quality ignores — so a document id can no longer be passed where a template's identity belongs. Named for the concept rather than the column: in `common.ids` a `…Id` is an address chain, which is the one thing an identity is not. Storage is unchanged (`uuid`), and so is every wire format; typed keys now bind through a shared `UuidIdArgumentFactory` rather than being unwrapped at each call site.
