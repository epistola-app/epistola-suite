---
type: fix
scopes: [data-contract]
audience: user
title: Saving a data contract no longer re-prompts for a breaking change already confirmed.
---

Confirming a breaking schema change and saving left the confirmation stale, so the very next save —
even one that only touched an example, not the schema — re-showed the same warning. Saving now
clears it once the breaking change is actually committed.
