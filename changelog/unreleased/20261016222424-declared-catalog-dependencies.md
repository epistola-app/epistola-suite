---
type: feat
scopes: [catalog, generation, api, mcp]
audience: user
breaking: true
title: A catalog declares the catalogs it uses, and a release renders with the releases it was published with.
---

Each release records the exact release of every catalog it uses and renders with it, whatever that catalog publishes later. The **Dependencies** page lists what a catalog uses and what uses it, with a drawing of how catalogs build on each other, and offers **Use the latest versions** when a newer release still fits what you use. A new catalog declares the system catalog from the start. Pickers offer the declared catalogs' resources, and those of other catalogs apart, asking to add the dependency when you choose one. Catalogs can no longer come to use each other. Over REST and MCP, using a resource of an undeclared catalog is refused with `CATALOG_NOT_DECLARED`: declare it first. The upgrade declares what existing catalogs use.
