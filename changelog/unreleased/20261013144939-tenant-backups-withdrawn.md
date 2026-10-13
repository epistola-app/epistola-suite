---
type: refactor
scopes: [support, backups]
audience: user
breaking: true
maturity: beta
issues: [1050]
title: Tenant backups are withdrawn; use a database-level backup instead.
---

Support → Backups, the beta per-tenant backup and restore, is removed together with its feature toggle, its schedule settings (`epistola.support.backups.*`, now ignored) and the `BACKUP_VIEW` and `BACKUP_CREATE` permissions. Released catalog content is now kept by its releases, which can be exported and deployed exactly as released, so a database-level backup covers what remains: unreleased edits, environments, API keys and settings. Existing tenant backups can no longer be restored; take a database-level backup before upgrading.
