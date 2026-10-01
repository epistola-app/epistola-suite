---
type: fix
scopes: [templates]
audience: user
issues: [1025]
title: Two variants of one template can no longer have the same attributes.
---

Such variants were accepted, but no request could tell them apart, so every attribute-based generation
or preview that reached them failed with `409 AMBIGUOUS_VARIANT`. Creating or updating a variant —
in the UI, over REST or over MCP — now refuses an attribute set another variant of the template
already has, and names that variant. Variants without attributes are exempt, and variants already
stored with a shared set are left as they are until edited.

The attribute documentation described a "most specific variant wins" tiebreak that selection never
performed; it now describes the scoring as it is.
