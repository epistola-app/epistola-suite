---
type: test
scopes: [exchange]
audience: dev
title: Enrollment through the public discovery document is now covered.
---

Every existing test configured `base-url`, the local-development escape hatch, which is how a production-only defect survived: discovery was skipped and nothing exercised it. The new tests leave `base-url` unset, serve the document shape epistola.app actually publishes, and cover the version guard, the issuer-mismatch guard, and the refusal of a plaintext Exchange.
