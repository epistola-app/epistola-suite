---
type: feat
scopes: [catalogs]
audience: user
title: An address a moved resource left behind is reserved.
---

Creating a resource at that address is rejected rather than silently repointing references published against it; releasing the alias is explicit and previews what stops resolving. Alpha, behind the `resource-relocation` toggle.
