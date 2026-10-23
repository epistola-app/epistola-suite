---
type: feat
scopes: [catalog, ui, mcp]
title: After a merge, a draft asks whether to remove the catalogs it no longer uses.
---

A merge can leave a draft declaring catalogs nothing in it uses any more. The draft's page now says so after the merge and offers to remove them, or keep them; nothing is removed unasked. MCP `merge_release` returns them as `unusedDependencies`, with a notice to ask the user. A catalog counts as used when a resource refers to it, and now also when it provides the catalog's default theme or an attribute a variant carries, so the Dependencies page lists those uses too.
