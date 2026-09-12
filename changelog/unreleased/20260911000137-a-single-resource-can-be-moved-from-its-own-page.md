---
type: feat
scopes: [catalogs]
audience: user
title: A single resource can be moved from its own page.
---

`/catalogs/organise/move?resource=<type>:<catalog>/<key>` shows one resource, where it can go, and what the move would rewrite. It answers with a dialog to HTMX, so a resource page can open it in place, and with a full page to a pasted link — one URL that works from either direction.
