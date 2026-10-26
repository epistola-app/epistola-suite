---
type: refactor
scopes: [catalog]
audience: dev
title: A release is only its version row; catalog_releases is gone.
---

`catalog_releases`, the mirror that kept it in step with the released rows of `catalog_versions`,
and the emptied `catalog_release_dependencies` are dropped (ADR 0036, step (e)). Every reader and
writer works on the version row: releasing freezes the draft's row with the release record,
installing inserts a released row, deleting a release deletes its row. A release's merge parents
name its row (`catalog_release_merges.version_id`), and the publication outbox names it by catalog
and number, both going with the release as before. Nothing changes for authors.
