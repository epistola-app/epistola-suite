---
type: fix
scopes: [generation]
audience: dev
title: Fixed a flaky database-pressure recovery test.
---

`JobPollerDatabasePressureIntegrationTest` synchronized on a drain-loop heartbeat that ticks before claiming happens, so on a slow CI runner the test could check `JobPoller.awaitIdle()` before the claim it was waiting on had even landed. Added `JobPoller.awaitDrain()`, a test-only synchronous drain that blocks on the drain pass's own `Future`, giving an exact happens-before guarantee with no added polling latency.
