---
type: feat
scopes: [mcp]
audience: user
title: MCP lists the tenant's environments and what is deployed to them.
---

`list_environments` returns every environment with the releases and drafts it serves and how many
documents were requested through each, so an assistant can find the `environmentId` that
`preview_document` and `analyze_template_data` take. It is read-only: deploying stays in the UI and
the REST API.
