---
type: feat
scopes: [catalogs]
audience: user
title: Resources are relocated in batches, and a relocation can rename.
---

A destination is now a full address, so a resource can change catalog, key, or both — moving and renaming are the same operation. A batch is all-or-nothing: one transaction, one plan, and any blocker stops every member, with blockers naming the member they belong to. A member may take an address another member is vacating; two members exchanging addresses is refused in the preview, because address uniqueness is checked per statement and no order avoids a transient collision.
