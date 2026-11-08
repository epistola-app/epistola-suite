---
type: feat
scopes: [generation]
title: Generation statistics per tenant, kept after documents and jobs are cleaned up.
---

The Generation page shows each month's documents generated and failed, and previews rendered, and the
last 30 days by day. The figures are rolled up in the background from the generation results, so
generating a document does no extra work, and a document deleted right after it was generated still
counts. Hours are kept for a week (`epistola.statistics.hourly-retention`), then days, indefinitely
([docs/generation-statistics.md](../../docs/generation-statistics.md) lists every setting).
Partition maintenance now records every monthly partition it drops, with its month, estimated row count
and size, in `partition_history`.
