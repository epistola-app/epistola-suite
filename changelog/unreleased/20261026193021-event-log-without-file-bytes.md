---
type: fix
scopes: [eventing]
audience: dev
title: The event log records an uploaded file by its size and hash, not its bytes.
---

Every completed command is written to `event_log` as JSON, and a command carrying a file (an
uploaded image, a font face, a catalog's binaries on install) stored the whole file there as base64.
The file is already stored once by its SHA-256, so the event now names it by `size` and `sha256`
instead, and `event_log` no longer grows by the size of every file installed or uploaded.
