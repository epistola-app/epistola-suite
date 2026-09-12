---
type: fix
scopes: [catalog]
audience: user
title: A catalog now says when it was installed.
---

`installed_at` has been in the schema since the first release, mapped on the catalog model, and reported to AI assistants through the MCP server — and written by nothing, so every catalog on every installation answered `null`. Registering, upgrading and importing a subscribed catalog all stamp it now. Authored catalogs keep reporting nothing, which is the honest answer: nobody installed them. Existing rows stay null rather than being backfilled with a moment that would be a guess.
