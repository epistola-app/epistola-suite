---
type: feat
scopes: [exchange]
audience: user
title: Publish catalog releases to Epistola Exchange.
---

A tenant can connect to Epistola Exchange and publish its catalog releases there. The release transaction stores the portable ZIP and succeeds independently of Exchange availability; cluster-safe background workers carry the submission, retain the archive until Exchange reaches a terminal decision, and keep connection credentials renewed on their own. The Exchange page shows publication activity across every catalog, a queued publication can be withdrawn, and a tenant administrator can disconnect. Two things are deliberately explicit rather than defaulted: a catalog's Exchange namespace is always chosen — correctable until a release has reached Exchange — and publishing is its own permission, so a release is never queued without a destination someone picked. Off by default (`epistola.exchange.enabled`).
