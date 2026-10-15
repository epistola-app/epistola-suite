---
type: feat
scopes: [mcp]
audience: user
title: update_template_draft can return the document as stored.
---

The server normalises a template document when it stores it: absent fields are added as `null` and
keys are sorted. With `returnContent: true`, `update_template_draft` returns that stored document as
`templateModel`, in the same form `get_template_content` gives, so an assistant can diff later edits
against it instead of re-reading the template. The default response is unchanged.
