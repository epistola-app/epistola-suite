---
type: test
scopes: [exchange, config]
audience: dev
title: The generic SQL-array column mapper has tests.
---

It is registered on the erased `List`, so it is reachable by every JDBI-mapped list field in the application while two columns motivated it; a narrower `List<String>` registration was tried and `ExchangeTenantConnection` cannot be mapped with it. That breadth is now written down rather than rediscovered from a failure.
