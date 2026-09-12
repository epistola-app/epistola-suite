---
type: fix
scopes: [migrations]
audience: dev
title: Relocation's schema changes land as one block, last.
---

They were timestamped `20260822`–`20260831`, straddling the `20260824` Exchange migrations that `main` already carries. Flyway runs with the default `outOfOrder=false`, so an installation that had applied the Exchange migrations would have refused to start on a lower-versioned pending one. All six are renumbered into a contiguous `20260905` block, keeping their relative order. Legitimate because none has ever been part of a release — the rule against editing a migration binds from the moment one ships.
