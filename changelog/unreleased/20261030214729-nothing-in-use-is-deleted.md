---
type: fix
scopes: [catalog, api]
breaking: true
title: A catalog can no longer be left naming what it does not have.
---

Deleting a stencil, font, image or catalog that something still used could be forced, a theme another catalog used could be deleted, saving content could name a stencil, image or catalog nobody has, and removing a dependency or deleting a release a draft read was accepted: each left a draft pointing at nothing, found only when its next release was refused. Each is now refused when it happens, naming what is missing or what uses it. A draft upgraded from 1.x that already names something missing can still be edited; publishing it asks for the fix, as before. The REST image delete still accepts `force`, but ignores it.
