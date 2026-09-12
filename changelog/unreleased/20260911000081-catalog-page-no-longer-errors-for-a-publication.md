---
type: fix
scopes: [exchange]
audience: user
title: Catalog page no longer errors for a publication with no failure.
---

The new authority link tested `failure?.needsAuthorityTransfer`, which evaluates to null for a healthy publication and cannot be converted to a boolean — so every catalog page that had ever published returned 500.
