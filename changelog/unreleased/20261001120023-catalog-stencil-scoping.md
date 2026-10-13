---
type: fix
scopes: [stencils, catalog]
audience: user
issues: [983, 1023, 1024, 1032]
title: Stencils are kept apart by catalog, and installed catalogs are fully read-only.
---

Stencils with the same key in different catalogs were mixed up when upgrading, deleting or listing usage. Five edits slipped past the read-only rule for installed catalogs; they are now refused. Upgrading a template's stencils from a draft to a published version now makes the template publishable.
