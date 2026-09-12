---
type: fix
scopes: [catalogs]
audience: dev
title: Two identity triggers no longer fail silently.
---

The registry's UPDATE branch would leave a stale address if its row were missing, making the resource addressable only at an address it no longer occupies; the generation-history fill assigned NULL when it matched no template, which since this release's dropped foreign keys means a row that is neither validated nor resolvable. Both now raise.
