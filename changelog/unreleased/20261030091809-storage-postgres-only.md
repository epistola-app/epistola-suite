---
type: feat
scopes: [storage]
breaking: true
maturity: alpha
title: Only PostgreSQL is supported for document storage.
---

The S3 and filesystem document backends were alpha and never tested in CI or in production. Epistola
now refuses to start with `epistola.storage.backend` set to `s3` or `filesystem`; set it to
`postgres`, the default. They will return once they are tested.
