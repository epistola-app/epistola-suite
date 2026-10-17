---
type: feat
scopes: [catalog]
breaking: true
title: The upgrade to 2.0 gives every catalog its declarations and a first release; nothing is deployed.
---

No 1.x release kept its content, and 1.x environments activated template versions rather than releases, so after the upgrade nothing could render. Shortly after the upgrade, a background task takes every authored catalog that existed before it and:

- declares each catalog its content references (1.x had no declarations, and 2.0 refuses to release an undeclared reference), with version `*`, locked to the highest release there is;
- releases it once from what was last published before 2.0, numbered as its draft. A template whose default variant was never published, and a stencil never published, are left out. Unpublished 1.x work stays in the draft that opens on top.

A subscribed catalog installed under 1.x gets the release it has installed recorded as one it can render from; a legacy publisher fingerprint, which names no algorithm, is replaced by the qualified fingerprint of the installed content, so catalogs that depend on it can lock to it.

**Operators must deploy these releases to their environments**: the upgrade deploys nothing, and generation for an environment fails until a release is deployed to it. A catalog that cannot be released yet, because a catalog it depends on has no release, is retried every five minutes; the reason is logged. Catalogs created after the upgrade are left to their authors.
