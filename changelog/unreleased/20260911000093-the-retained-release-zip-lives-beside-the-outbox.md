---
type: refactor
scopes: [exchange]
audience: dev
title: The retained release ZIP lives beside the outbox row, not inside it.
---

`catalog_release_publications` is a work queue the cluster worker polls on a fixed delay, and holding multi-megabyte archives in it made releasing one an `UPDATE ... SET archive = NULL` — a dead tuple plus orphaned TOAST chunks in the hot table, waiting on autovacuum. The bytes move to `catalog_release_publication_archives`, so releasing them is a `DELETE` that reclaims cleanly, `size_bytes` is stored rather than measured by detoasting every archive to weigh it, and there is now something a retention sweep could be built against. Same shape as `document_content`, split out for the same reason in #738. Changed before release because it is the one part of this schema that is genuinely expensive to change afterwards: a live outbox would need a new table, a backfill of in-flight rows, and a column drop with work in the queue throughout.
