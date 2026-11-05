---
type: docs
scopes: [support-policy, security]
title: The support policy now gives each release line a fixed end after its successor ships.
---

Only the newest minor line of a major is patched. When a new minor ships, the previous one stays
supported for 30 days; when a new major ships, the last minor of the previous major stays supported
for three months, with security and data-loss fixes. End dates are announced in advance, published
in the release metadata the version check reads, and may be extended but never brought forward.
This replaces "the two most recently released minor lines".
