---
type: fix
scopes: [exchange]
audience: user
title: Disconnecting resolves queued publications instead of leaving them to retry forever.
---

Work that had lost its credentials was re-claimed indefinitely, holding a retained release archive each. It is now failed with the reason, keeping the archive so reconnecting and retrying still works.
