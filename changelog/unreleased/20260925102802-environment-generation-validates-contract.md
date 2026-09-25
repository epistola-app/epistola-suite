---
type: fix
scopes: [documents, api]
audience: user
issues: [980]
title: Generating or previewing a document for an environment validates its data against the template's data contract again.
---

A request that resolved its template version through `environmentId` skipped data-contract
validation, so data missing a required field rendered a document with that field left empty, while
the same request without an environment was rejected. Environment-based requests are now validated
like any other: generation fails the job and preview returns `400` with
`Data validation failed`. Integrations that were sending incomplete data for an environment will now
see those requests rejected, and should send the fields their contract requires. Contract defaults
and preview's fall-back to the contract's first data example now apply on this path as well.
