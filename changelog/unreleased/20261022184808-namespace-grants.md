---
type: feat
scopes: [catalog, exchange, mcp]
breaking: true
title: Namespaces are granted to tenants, and a new catalog is named in one its tenant holds.
---

A tenant holds namespaces: granted by its Epistola Exchange connection, added under one of the organisation's domains by the tenant's administrator (**Settings, Authorities & namespaces**), or held from before, as every tenant holds the namespace its authored catalogs were bound to on Epistola Exchange (catalogs it authored otherwise are under `local`, which needs none). A new catalog is named in one its tenant holds, chosen when it is created, or under `local` with a namespace of its own; nothing is named after the tenant key any more, and a new tenant starts under `local`. Releasing needs the catalog's namespace. A catalog under `local` is never published to Epistola Exchange; it is released, exported and imported like any other. MCP `create_catalog` takes `namespace` and `authority`; `list_namespaces` lists what the tenant holds. **Action on upgrade:** none for existing catalogs; a tenant's administrator adds the namespace of a catalog it received and should release.
