---
type: fix
scopes: [security, editor]
audience: user
title: Previewing a template in the editor needs permission to view it, not to generate documents.
---

The editor's preview asked for `DOCUMENT_GENERATE`, so content viewers and authors without the
document generator role could not see what they were reviewing. A preview brings its own data and
stores nothing, so `TEMPLATE_VIEW` is enough. Previews through the REST API and MCP still need
`DOCUMENT_GENERATE`, as the contract declares.
