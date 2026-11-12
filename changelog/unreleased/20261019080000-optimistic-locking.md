---
type: feat
scopes: [editor, api, mcp]
audience: user
title: Two people editing the same template, theme or stencil no longer overwrite each other unnoticed.
---

The editors and edit dialogs save against the revision they opened. When someone else saved in between, they ask whether to replace that version or reload it, instead of silently replacing it. Variants have revisions of their own, so two people editing different variants of one template do not conflict. The REST API returns the revision as an `ETag`, and an update that sends it in `If-Match` is refused with 412 `resource-modified` when the resource changed since. MCP read tools return `revision` and update tools take `expectedRevision`.
