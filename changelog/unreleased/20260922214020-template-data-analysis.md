---
type: feat
scopes: [api, mcp]
issues: [978]
title: Preview says which data fields are missing or wrong, with a JSON Schema for what is missing.
---

A REST preview whose data breaks the template's data contract used to fail with one flattened
message. It now answers with a `template-data-invalid` problem (still status 400): `errors[]` lists
each field as a JSON Pointer into the request body, and `missingFields`, `invalidFields` and
`missingDataSchema` describe what to supply or correct. `missingDataSchema` is the contract cut down
to the missing part, so a downstream app can render a form for it. Required fields are always
reported; optional ones only when the template reads them. The new MCP tool
`analyze_template_data` returns the same analysis without rendering.
