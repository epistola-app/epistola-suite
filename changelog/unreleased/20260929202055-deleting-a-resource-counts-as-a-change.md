---
type: fix
scopes: [catalog]
audience: user
issues: [1037]
title: Deleting a resource now counts as an unreleased change.
---

A catalog whose only change since its last release was a **deletion** reported no unreleased changes,
while the release dialog correctly said the next release would drop that resource. So the catalog
page told you there was nothing to review, right before releasing removed something.

The cause was the cheap signal behind the badge: it compares the newest timestamp across a catalog's
resources against the last release, and deleting a resource moves no timestamp — the row simply goes,
and the newest one left can even be older. Deleting a resource now marks the catalog as changed, so
the badge and the release dialog agree. It remains conservative in one direction only: it may still
over-warn after an edit-and-revert, and no longer stays quiet about a removal.
