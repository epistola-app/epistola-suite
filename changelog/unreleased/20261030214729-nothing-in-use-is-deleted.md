---
type: fix
scopes: [catalog, api]
breaking: true
title: Nothing in use is deleted any more, and a catalog still in use stays declared.
---

Deleting a stencil, font, image or catalog that something else still uses could be forced, leaving what used it pointing at nothing until someone found it, and the next release refused. It is now always refused, naming what uses it; stop using it first. The REST image delete still accepts `force`, but ignores it. Removing a dependency on the Dependencies page, over REST or MCP while the catalog still uses something from it is refused the same way, instead of being accepted silently and refused later at release.
