---
type: feat
scopes: [catalog]
audience: user
breaking: true
title: Any catalog can be changed unless the installation is run-only, and receiving a release merges into it.
---

A catalog received from the Exchange, a source or an archive was read-only. Now every catalog has a next version, and a catalog can be changed and released wherever the installation allows editing; only the catalogs Epistola supplies (system, demo) stay read-only. `epistola.catalog.editing.enabled: false` (Helm `catalog.editing.enabled`) makes an installation run-only: it receives, deploys and rolls back releases, and nothing is changed in it, typically production. Receiving a newer release moves an unchanged next version to it; a next version with changes of its own keeps them, and the release can be merged into it. An upgrade from a source is refused while the next version has changes of its own.
