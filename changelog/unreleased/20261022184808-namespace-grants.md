---
type: feat
scopes: [catalog, exchange, mcp]
breaking: true
title: Namespaces are granted to tenants, and a new catalog is named in one its tenant holds.
---

A tenant holds namespaces: granted by its Epistola Exchange connection, by a platform administrator (tenant list, **Namespaces**), or held from before, as every tenant holds those of the catalogs it authored. A new catalog is named in one its tenant holds, chosen when it is created, or under `local` with a namespace of its own; nothing is named after the tenant key any more, and a new tenant is created with its namespace. Releasing needs the catalog's namespace. A catalog under `local` is never published to Epistola Exchange, and is not exported until the catalog format carries `local`. MCP `create_catalog` takes `namespace` and `authority`; `list_namespaces` lists what the tenant holds. **Action on upgrade:** none for existing catalogs; grant a namespace to a tenant that should release a catalog it received.
