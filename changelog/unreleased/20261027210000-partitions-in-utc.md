---
type: fix
scopes: [operations]
title: Monthly partitions are bounded at UTC midnights on nodes whose JVM runs in another time zone.
---

Partition maintenance created the monthly partitions of documents, generated content, generation
results, the event log and the audit log with bounds read in the JVM's time zone. On a node outside
UTC those bounds were shifted by its offset, so rows near the start of a month could land in the
wrong partition or in none. Partitions are now always created in UTC.
