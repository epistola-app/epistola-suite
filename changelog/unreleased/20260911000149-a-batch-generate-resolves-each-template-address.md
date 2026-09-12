---
type: perf
scopes: [api]
title: A batch generate resolves each template address once, not once per item.
---

Every item did its own canonical-address lookup — a query and a pool checkout each, serially, before the batch command was dispatched — in a handler otherwise carefully batched. Batches are uncapped, so the cost grew with the request.
