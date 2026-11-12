---
type: feat
scopes: [catalog, mcp, api]
audience: user
title: A draft can merge a release it is behind, resource by resource (alpha).
---

A draft that is behind a release, such as the next release after a fix was published from parallel work, can merge it. The merge takes what only the release changed, keeps what only the draft changed, and asks for a choice where both changed the same resource, or for combined content. It compares against the release both came from, and refuses a result that would refer to something missing. Available on the draft's and the catalog's page, over MCP (`preview_merge`, `get_merge_sides`, `merge_release`) and over REST.
