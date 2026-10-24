---
type: feat
scopes: [catalog, ui, mcp, rest]
title: A catalog is named by its namespace and key, and two catalogs with one key live side by side.
---

Within a tenant a catalog is named by its namespace and key, its address (`acme/letters`), and everything that stores a reference to one keeps the catalog's id, so two catalogs with one key (`acme/letters` and `bureau/letters`) work everywhere: created, imported, installed from Exchange or a registry, rendered, deployed, snapshotted and restored, checked for updates and searched. There is no switch for it. A catalog's pages are at `/tenants/{tenant}/catalogs/{namespace}/{key}/…`, and every page links each catalog by its address; an address by key alone redirects to the catalog's address while the key names one catalog, and is refused, naming the catalogs, when it names several. Over MCP a tool's `catalogId` is `namespace/key`, or the key alone while one catalog has it. REST paths already name the namespace. Content still names another catalog by its key (the alias it is declared under), and each such reference is resolved through the dependencies of the catalog the content is in.

Tenant snapshots are schema 4: each catalog is filed under `catalogs/{namespace}/{key}.zip` and named by its address; a snapshot from before restores as it did. Quality finding URNs (alpha) name the template's catalog by its address, `tenant/namespace/key/template`; a migration rewrites the URNs already recorded, so ignores keep their findings.
