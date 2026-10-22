---
type: feat
scopes: [catalog, ui, mcp]
title: Two catalogs with one key live side by side, told apart by namespace.
---

Within a tenant a catalog is unique by namespace and key, so `acme/letters` and `bureau/letters` can both be installed, authored and released. A catalog's pages are at `/tenants/{tenant}/catalogs/{namespace}/{key}/…`; an address by key alone still opens the one catalog with that key, and is refused, naming the catalogs, when the key names several. Over MCP a tool's `catalogId` may be written `namespace/key` (`list_catalogs` gives each catalog's `address`). A catalog received from another namespace is installed beside one of the tenant's own with the same key instead of being refused.
