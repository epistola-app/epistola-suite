---
type: test
scopes: [exchange]
audience: dev
title: The Exchange install tests share one fake and one context.
---

They were written before `SharedFakeExchange` and its two base classes existed, so each stood up its own server and its own Spring context — the exact cost that change had just removed. The upstream-check test keeps its own, because it is the one that needs the background worker switched on. The aborted-install rollback also now asserts it leaves no resource identities behind: relocation mints those from a database trigger on every resource insert, so a rollback that only removed the catalog would leave the registry describing resources that no longer exist.
