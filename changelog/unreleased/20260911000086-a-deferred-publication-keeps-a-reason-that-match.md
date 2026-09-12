---
type: fix
scopes: [exchange]
audience: dev
title: A deferred publication keeps a reason that matches itself.
---

`defer` replaced the supporting detail unconditionally while preserving the previous failure code, so a row could end up describing a code from one failure with the detail of another. A reason is now replaced as a unit, and the row stamps `updated_at` like every other write.
