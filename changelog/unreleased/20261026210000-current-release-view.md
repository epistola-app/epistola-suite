---
type: refactor
scopes: [catalog]
audience: dev
title: A catalog's current release and its next version are no longer flags or pointers kept by hand.
---

The catalog's current release is a view over its released rows (`catalog_current_releases`), so
`catalogs.released_version`, `released_fingerprint` and `released_at` are dropped, and deleting a
release has nothing to repoint. The next version is `catalogs.next_version_id` alone: the
`catalog_versions.is_next` flag and the trigger that kept the two in step are gone, and one check at
commit holds that the next version is a draft of its catalog and that a draft without a name is the
next one (ADR 0036, step (e)). Nothing changes for authors.
