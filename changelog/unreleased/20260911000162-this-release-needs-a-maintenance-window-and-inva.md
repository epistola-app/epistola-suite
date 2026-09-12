---
type: feat
scopes: [db]
audience: user
breaking: true
title: This release needs a maintenance window, and invalidates existing tenant backups.
---

Re-keying every catalog resource onto a stable identity drops the address columns from nine tables, so application code from the previous version cannot read the new schema. The `pre-upgrade` Job commits it while the old pods are still serving, and the rolling update then replaces them a quarter at a time — so the upgrade must be run with the application scaled to zero. Separately, every tenant backup taken before the upgrade becomes unrestorable the moment it commits (the column sets genuinely differ, so the compatibility headers say so) — take a database-level backup first, and a fresh tenant backup afterwards rather than waiting for the daily schedule. Full procedure in the new [Upgrades](docs/upgrades.md) guide.
