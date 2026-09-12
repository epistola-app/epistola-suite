---
type: fix
scopes: [test]
audience: dev
title: The test Postgres container moves to 18, and its tmpfs with it.
---

PostgreSQL 18's image stores its cluster in a major-version subdirectory (`/var/lib/postgresql/18/docker`) and refuses to start if it finds a mount on the old `/var/lib/postgresql/data` path — so the version bump alone failed every context in the suite until the tmpfs moved up one level. It is still RAM-backed.
