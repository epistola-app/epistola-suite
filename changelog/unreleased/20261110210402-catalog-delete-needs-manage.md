---
type: fix
scopes: [catalog, ui]
audience: user
title: The catalogs list offers Delete only to people who may delete a catalog.
---

Every row offered **Delete** to anyone who could see the list, including publishers and authors, and the server then refused it for want of `CATALOG_MANAGE`. The control now appears only for tenant administrators, as **Import** already did.
