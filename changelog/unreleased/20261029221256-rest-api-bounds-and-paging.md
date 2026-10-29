---
type: feat
scopes: [rest-api]
breaking: true
title: Generation jobs list their items a page at a time, and code-list entries are paged.
---

A generation job's status is the job and its counts; its items are listed by
`GET …/documents/jobs/{requestId}/items`, a page at a time and optionally by status, and an item no
longer echoes its data. A code list's entries are paged in the database (`page`, `size`, and a `page`
in the response). Lists that sorted by nothing no longer take `sort` or `direction`, attributes no
longer take a `description` (none was ever kept), and a code list's source and authentication types
are named enums, as the 2.0 contract has them.
