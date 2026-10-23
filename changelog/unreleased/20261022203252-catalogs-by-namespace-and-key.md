---
type: feat
scopes: [catalog, ui, mcp, rest]
title: Two catalogs with one key live side by side, told apart by namespace.
---

Within a tenant a catalog is unique by namespace and key, so `acme/letters` and `bureau/letters` can both be installed, authored and released. A catalog's pages are at `/tenants/{tenant}/catalogs/{namespace}/{key}/…`; an address by key alone still opens the one catalog with that key, and is refused, naming the catalogs, when the key names several. Over MCP a tool's `catalogId` may be written `namespace/key` (`list_catalogs` gives each catalog's `address`). A catalog received from another namespace is installed beside one of the tenant's own with the same key instead of being refused. Every page listing a tenant's catalogs (the home page, the catalog list, the dependency and resource graphs, the catalog switcher, an environment, generation history) shows the two as two entries, each linked by its namespace, and a page reached by namespace links its catalog the same way. REST `listCatalogs` and MCP `list_catalogs` give each its own editability, REST environment deployments name each by its own catalog, and MCP `apply_house_theme` names the catalogs it changed by `address` (`namespace/key`) instead of by key.
