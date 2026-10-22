---
type: feat
scopes: [api, mcp, catalog]
breaking: true
title: Over REST and MCP, a draft that changed nothing has nothing to release.
---

`getCatalogChanges` lists what changed beside the resources as `catalogChanges` (a dependency by its alias, the default theme, the presets, the license, each added, modified or removed), says per resource whether it is merged content awaiting review (`mergedForReview`), and calls a draft `releasable` only when something changed against its base, every changed resource is ready and no merged content awaits review. Releasing a draft that changed nothing is refused with 409 `conflict` over REST and refused by MCP `release_draft`, whose `get_catalog_changes` answers `releasable` the same way. **Action on upgrade:** a client that re-released unchanged content, for new notes, can no longer; check `releasable` before releasing.
