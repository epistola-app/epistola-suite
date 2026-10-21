---
type: fix
scopes: [catalog, exchange]
audience: user
title: Importing an archive, and getting a release from Exchange or a registry, takes the catalog-management permission alone.
---

The web UI's _Import ZIP_ checked the template-edit permission while the import itself requires
catalog management, so an author was offered an import that was then refused, and a tenant
administrator was not offered one they may do. Installing from Epistola Exchange or a container
registry asked for both permissions. Receiving a release writes no draft, so each now checks
catalog management only, where the control is shown and where it is used, as REST and MCP do.
