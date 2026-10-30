---
type: refactor
scopes: [support, catalog]
breaking: true
title: Tenant snapshots and Support → Upgrading are withdrawn.
---

Tenant snapshots exported each catalog's work in progress as a catalog archive, which the 2.0 catalog
format no longer allows for a catalog that depends on one never released. They are removed, and with
them Support → Upgrading, whose compatibility checks the hub ran on those snapshots: its page, its
`support-compatibility-check` feature toggle, the snapshot freshness sweep and its
`epistola.support.upgrading.*` properties (now ignored), and the `TENANT_RESTORE` permission, which
only restoring a snapshot used. Released catalog content is kept by its releases, which can be
exported and deployed exactly as released; a database-level backup covers the rest.
