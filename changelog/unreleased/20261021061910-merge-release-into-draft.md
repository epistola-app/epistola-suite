---
type: feat
scopes: [catalog, mcp, api]
audience: user
title: A draft can take in a release it is behind, resource by resource (alpha).
---

A draft that is behind a release, such as the next version after a fix was released from other work or a release received from another installation, can now merge it instead of only acknowledging it. The merge takes what only the release changed, keeps what only the draft changed, and asks for a choice where both changed the same resource. A release that removes something the draft still uses is refused. Available in the web UI from the draft's page, over MCP as `preview_merge` and `merge_release`, and over REST as `previewCatalogDraftMerge` (`GET …/releases/{draft}/merge-preview?version=`) and `mergeIntoCatalogDraft` (`POST …/releases/{draft}/merge`), with the problem types `merge-conflicts-unresolved`, `merge-refused` and `merge-not-applicable`. Merging within one resource is not supported yet.
