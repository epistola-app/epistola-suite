---
type: feat
scopes: [api, mcp]
issues: [978]
title: Preview says which data fields are missing or wrong.
---

A REST preview whose data breaks the template's data contract used to fail with one flattened
message. It now answers with a `template-data-invalid` problem (still status 400): `errors[]` lists
each field as a JSON Pointer into the request body, and `missingFields` and `invalidFields` give
each field's pointer into `data` and its JSON Schema, so a downstream app can ask for exactly those.
Required fields are always reported; optional ones only when the template reads them. The new MCP
tool `analyze_template_data` returns the same analysis without rendering, and needs only the
template view permission.
