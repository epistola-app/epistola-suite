---
type: refactor
scopes: [support, backups]
breaking: true
maturity: beta
title: Tenant backups, tenant snapshots and Support → Upgrading are withdrawn; use a database backup.
---

A release now keeps its content and can be exported and deployed exactly as it was published, so a database-level backup covers the rest: drafts, environments, API keys and settings. Removed: Support → Backups with `epistola.support.backups.*`, `BACKUP_VIEW` and `BACKUP_CREATE`; tenant snapshots and Support → Upgrading with the `support-compatibility-check` feature toggle, `epistola.support.upgrading.*` and `TENANT_RESTORE`. The properties are ignored. Existing tenant backups cannot be restored: take a database-level backup before upgrading.
