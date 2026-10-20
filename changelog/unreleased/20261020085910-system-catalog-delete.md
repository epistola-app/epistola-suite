---
type: fix
scopes: [catalog]
title: The system catalog can no longer be deleted.
---

The catalogs page offered to delete the system catalog, and deleting it went through, forced or not, on a tenant where nothing else referred to it. Its fonts and every release that pins it depend on it. It is now refused like the default catalog, and its delete button is disabled.
