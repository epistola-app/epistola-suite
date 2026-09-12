---
type: refactor
scopes: [exchange]
audience: dev
title: Publication reaches the outbox through a catalog-owned port.
---

Catalog release code no longer references the Exchange integration: it hands the open release transaction to `CatalogReleasePublicationPort`, so the outbox write stays atomic while the dependency points the right way. Availability, namespace binding, outbox SQL and the UI's publication rules each gained a single owner, and publication status is a typed lifecycle instead of strings spread across the schema, worker, queries and templates.
