---
type: feat
scopes: [rest-api]
breaking: true
title: The REST API follows the 2.0 contract's names and references.
---

A variant's `title` and an attribute's or code list's `displayName` are `name`, in requests,
responses and the field a refusal names. A document's `id` is `documentId`, a generation job's
`requestId` and a generation item's `itemId`, and `draftLabel` is `releaseName`. Listing images
filters with `q`. Install and import name each release they brought along in full (registry,
namespace, catalog key, version and release id) rather than as `key vVERSION`. A template's
`dataModel` is always present, `{}` when it has no constraints, and a refused data example is
reported under its key, which is unique, rather than its name, which need not be.
