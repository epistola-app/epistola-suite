---
type: fix
scopes: [catalog]
title: The release a catalog installed is read from its releases, never a pointer beside them.
---

What a catalog has installed was three columns on the catalog, set before the release they named
was recorded and holding the publisher's fingerprint as given; a trigger moved the next version
along with them. Receiving a release now records it first and then moves the next version to it,
and the installed release is the nearest received release the next version descends from (the
view `catalog_installed_releases`), so the two cannot disagree. The columns
`catalogs.installed_release_version`, `installed_fingerprint`, `installed_resource_fingerprints`
and `installed_at` are gone; a 1.x catalog's installed release reaches the 2.0 upgrade through its
queue. A database created from an earlier 2.0 development build needs rebuilding.
