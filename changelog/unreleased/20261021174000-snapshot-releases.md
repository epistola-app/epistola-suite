---
type: fix
scopes: [catalog]
audience: user
title: A restored tenant snapshot keeps where catalogs come from and what they released.
---

Restoring a tenant snapshot brought every catalog back without its source and without its
releases, so a received catalog no longer knew where to get updates and a catalog released here
could not render, merge or continue from its releases. Snapshots (schema 3) now carry each
catalog's source (its address and how it authenticates, never the credential, which is given again
after a restore), the release it received last, the release its next release is on, and its
releases with their content, ids, fingerprints, bases and who released them. Older snapshots still
restore as before. The snapshot fingerprint now changes when a release is cut, so a new release
makes a new backup.
