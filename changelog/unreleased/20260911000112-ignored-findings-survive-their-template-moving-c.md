---
type: fix
scopes: [quality]
audience: dev
title: Ignored findings survive their template moving catalogs.
---

Finding and ignore scope URNs embed the subject's address and are the join between the two, so a move would have left ignores stale and silently reopened every ignored finding on the next submission. Quality repoints its own rows through an immediate event handler, so core still knows nothing about the module.
