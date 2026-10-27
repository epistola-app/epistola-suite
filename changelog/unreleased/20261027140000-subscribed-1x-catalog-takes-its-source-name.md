---
type: fix
scopes: [catalog]
title: A catalog 1.x subscribed to from a URL receives its source's release itself, not as a second catalog.
---

1.x catalogs had no namespace, so the 2.0 upgrade names a subscribed one in its tenant's, while its
source names it in its publisher's. At the first boot after the upgrade, such a catalog now takes
the registry and namespace its source gives it before its release is installed, and the drafts that
declare it follow. Before, the release was installed as a new catalog under the source's name, and
the tenant's own catalog stayed without a release.
