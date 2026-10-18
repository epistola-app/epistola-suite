---
type: fix
scopes: [catalog]
audience: user
issues: [1037]
title: Deleting a resource now counts as an unpublished change.
---

A catalog whose only change since its last release was a deletion reported no pending changes, because the signal behind the badge compared timestamps and deleting a resource moves none. So the catalog list told you there was nothing to review right before publishing removed something. Deleting a resource now marks the catalog as changed, so the badge and the publish dialog agree.
