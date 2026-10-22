---
type: feat
scopes: [api, mcp, catalog]
title: Over REST and MCP, a release carries its content hash and is found by it.
---

Every release the REST API returns says its `contentHash`, what identifies its content whatever its number or catalog: the release list and page, the result of releasing a draft, a registry's releases, a merge base, the release a dependency pins and a catalog's highest release. `listCatalogReleases?contentHash=` lists the releases with that content, an empty list when none has it. MCP `list_catalog_releases` takes the same filter, and `release_draft`, `list_catalogs` and `list_catalog_dependencies` return the hash too. A version range may now be up to 256 characters.
