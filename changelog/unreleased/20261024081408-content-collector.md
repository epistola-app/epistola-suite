---
type: feat
scopes: [storage, catalog]
audience: dev
title: Release content nothing points at any more is collected nightly, and can be collected by hand.
---

Content a release kept, which no release or other content points at any more, is deleted after a grace period by a nightly collector, with the files only it needed. Operators can move or switch off the run (`epistola.storage.gc.enabled`, `.cron`, default nightly at 02:00, `.grace`, default one day, `.batch-size`, `.pause`) and start it for a tenant from Operations → Cluster → Stored content. It works in small batches and never deletes content a release points at or a save is reusing; `epistola.storage.orphaned_contents` shows what is left. Writing an image's bytes that are stored already now restarts their grace period, so an upload that reuses an old file is never swept from under it.
