---
type: perf
scopes: [catalog]
audience: dev
title: A published release no longer serializes its manifest twice, and publication state is resolved once per release request.
---

Saving catalog publication settings reads and writes the row once instead of three times.
