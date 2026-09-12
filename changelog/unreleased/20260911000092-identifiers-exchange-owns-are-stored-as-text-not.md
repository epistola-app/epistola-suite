---
type: refactor
scopes: [exchange]
audience: dev
title: Identifiers Exchange owns are stored as `TEXT`, not mirrored with its length limits.
---

The connection reference, namespaces and scopes were typed to Exchange's exact sizes — `VARCHAR(29)` fits `tc_` plus 26 Crockford characters precisely. In PostgreSQL that is identical storage to `TEXT`, so the limit bought no space and only re-enforced a rule this side does not define: if Exchange ever lengthens a reference or relaxes a namespace limit, a mirrored limit turns that into an insert failure during enrollment or publication. Status columns keep their `CHECK` — those are this application's own state machine.
