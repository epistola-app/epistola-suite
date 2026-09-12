---
type: feat
scopes: [exchange]
audience: user
breaking: true
title: Publishing to Exchange is now its own permission, and a release is never queued without a destination.
---

`CATALOG_PUBLISH` covers sending a release out of this installation and choosing the namespace it lands in; `TEMPLATE_PUBLISH` still means cutting a release here. Releasing and publishing are different acts, so someone can be trusted with one and not the other. The publisher role gains the new permission.
