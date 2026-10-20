---
type: feat
scopes: [editor, api, mcp]
title: Two people editing the same template, theme or stencil no longer overwrite each other without noticing.
---

Templates, variants, themes, stencils, code lists and attributes carry a revision that every change raises. The template, theme, stencil and data contract editors save against the revision they opened, and when someone else saved in between they ask whether to replace that version with yours or reload to see it, instead of silently replacing it. The REST API returns the revision as an `ETag` on reads and on successful updates; an update that sends it back in `If-Match` is refused with `resource-modified` (412) when the resource changed since, and one without `If-Match` writes as before. A PATCH that merges with the current state (code lists, attributes, variants) is always guarded against a write landing during the merge. MCP read tools return `revision`, and update tools take it back as `expectedRevision`. Saving content that did not change keeps the revision, and making another variant the default does not count as a change to this one.
