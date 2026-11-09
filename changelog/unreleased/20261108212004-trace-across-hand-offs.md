---
type: fix
scopes: [observability]
audience: dev
title: Work handed to another thread logs with the trace of the request that handed it off.
---

Every request already carries a trace (Spring Boot's OpenTelemetry bridge puts `traceId` and `spanId`
in the log context), but work a command or query handed to another thread, such as a virtual thread
through `MediatorContext.runnable`, lost it: its log lines had no trace and span id, in the console's
structured log and in the application log under Platform → Logs. They now keep the request's.
