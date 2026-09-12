---
type: test
scopes: [migrations]
audience: dev
title: The RC1 preservation fixture seeds documents and attributes.
---

The two tables touched by the identity migrations -- the attribute primary-key swap and the generation-history foreign-key drops on a partitioned table -- had no RC1-era rows in `DataPreservationMigrationIT`. It now plants one of each and checks the attribute gained a registry identity while the document kept its address and, deliberately, no backfilled identity.
