---
type: fix
scopes: [api, security]
title: A REST write needs only the permission the contract declares for it.
---

Building the answer to a write, or a draft opened from a release, asked the caller for view
permissions the operation does not declare. A key scoped to Author was refused `updateTemplate` with
`TEMPLATE_VIEW`, a publisher discarding the next version with `CATALOG_VIEW`. Every REST operation
now enforces exactly its contract's `x-required-permissions`, and a refusal names that permission.
