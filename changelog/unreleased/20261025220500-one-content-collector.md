---
type: refactor
scopes: [storage, catalog]
title: One collector deletes stored content and the image and font bytes nothing holds; deleting a release or a catalog leaves their content to it.
---

The content collector (`epistola.storage.gc.*`) is now the only thing that deletes image and font bytes: after the catalog content nothing points at, it deletes the bytes no content holds, past the same grace period (default one day), and publishes `epistola.storage.orphaned_blobs{namespace=asset}` beside `epistola.storage.orphaned_contents`. The content reaper's own sweep of those bytes is gone, and with it the setting `epistola.storage.reaper.asset-grace-minutes`.

The reaper is now the document retention task (`core.document-retention`): it only drives the filesystem document backend's age sweep, still configured with `epistola.storage.reaper.enabled` and `epistola.storage.reaper.cron`. Its scheduled-task metric is `document-retention` instead of `content-reaper`.

Deleting a release or a catalog removes their rows at once and no longer deletes the content only they held inside the same transaction; the collector takes it on its next run past the grace period, as it does any other orphan. That keeps the delete from scanning the whole tenant, and from taking content a concurrent save was about to point at.
