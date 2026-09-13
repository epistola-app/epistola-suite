---
type: feat
scopes: [generation]
audience: dev
title: Document workers now yield to a degraded database.
---

Every JDBI statement records a safe aggregate round-trip timer (bound through Micrometer after bootstrap, so it never joins the JDBI-to-installation-metadata startup cycle); workers combine its rolling latency with Hikari pool contention to lower their effective render concurrency under sustained pressure via a hysteretic NORMAL→THROTTLED→PAUSED→RECOVERING state machine. A database cancellation, timeout, or connectivity failure pauses only new claims until a healthy recovery window has elapsed — in-flight documents continue normally. Enabled by default; exports pressure state, effective concurrency, and throttle/pause counters for Prometheus, and logs once on entering/leaving a pause rather than on every poll cycle.
