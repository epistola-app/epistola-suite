---
type: feat
scopes: [storage, upgrade]
title: The upgrade to 2.0 moves the last 1.x stored files itself and removes the old content store.
---

An installation that upgrades from 1.x before its background content move finished no longer depends on that job: the upgrade moves the remaining document and image files into their stores during the migration step, links font files that only now arrive to their fonts, and then removes the 1.x `content_store` table. A file whose bytes were already missing in 1.x is reported in the log and left as it was. The background job and the read fallbacks that served unmoved files are gone, along with the `epistola.storage.backfill.*` settings.
