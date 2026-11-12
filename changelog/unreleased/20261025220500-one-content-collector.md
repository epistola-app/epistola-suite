---
type: feat
scopes: [storage]
audience: dev
title: One nightly collector deletes stored content and files nothing uses any more.
---

Release content, images and font files that nothing points at are deleted after a grace period by a nightly collector (`epistola.storage.gc.enabled`, `.cron`, default 02:00, `.grace`, default one day, `.batch-size`, `.pause`). An installation administrator can also start it under Platform → Cluster. `epistola.storage.orphaned_contents` and `epistola.storage.orphaned_blobs` show what is left. Removed: `epistola.storage.reaper.asset-grace-minutes` and `epistola.storage.backfill.*`, since the upgrade moves the last 1.x stored files itself. The event log records an uploaded file by size and hash instead of its bytes.
