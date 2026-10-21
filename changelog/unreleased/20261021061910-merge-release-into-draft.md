---
type: feat
scopes: [catalog, mcp, api]
audience: user
title: A draft can take in a release it is behind, resource by resource (alpha).
---

A draft that is behind a release, such as the next version after a fix was released from other work or a release received from another installation, can now merge it instead of only acknowledging it. The merge takes what only the release changed, keeps what only the draft changed, and asks for a choice where both changed the same resource. A release that removes something the draft still uses is refused. Available in the web UI from the draft's page, over MCP as `preview_merge` and `merge_release`, and over REST as `previewCatalogDraftMerge` (`GET …/releases/{draft}/merge-preview?version=`) and `mergeIntoCatalogDraft` (`POST …/releases/{draft}/merge`), with the problem types `merge-conflicts-unresolved`, `merge-refused` and `merge-not-applicable`. The catalog's default theme, presets and dependency declarations merge the same way, each as one entry. A merge confirmed against a preview that is out of date, because the draft or the release changed since, is refused and asks to preview again (MCP: `preview_merge` returns a `token` that `merge_release` takes; REST: the preview's `token`, sent as `expectedToken`, refused with `merge-preview-outdated`). Merging within one resource is not supported yet.

The merge compares against the release both the draft and the release come from, found by the base each release names (catalog archives now carry it), never guessed from the release numbers. When this installation does not have that release, the preview says which one to get first (from a registry, the Exchange or a file) and the merge is refused (REST `missingBase` and `merge-base-missing`); when no release both come from is known, every difference is a choice. Resources are compared by their complete fingerprint, so a change to only a template's description or a variant's metadata counts as a change.
