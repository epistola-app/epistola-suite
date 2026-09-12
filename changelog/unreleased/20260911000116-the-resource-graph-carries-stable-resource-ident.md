---
type: feat
scopes: [catalogs]
audience: dev
title: The resource graph carries stable resource identities.
---

Nodes now expose the `resource_id` that survives a relocation, so a caller can follow a resource across a move instead of guessing where it landed from its new address.
