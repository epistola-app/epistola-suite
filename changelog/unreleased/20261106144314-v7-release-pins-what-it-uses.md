---
type: fix
scopes: [catalog]
audience: user
issues: [1319]
title: A catalog archive from 1.x that uses another catalog pins it when received.
---

A 1.x (wire v7) archive whose templates used stencils, images or themes from another catalog, or the
system catalog's locale attribute, was received as a release that pinned none of them and left the
system catalog undeclared. Copying that release into a draft then failed with
`CATALOG_DEPENDENCY_CATALOG_VERSION_INVALID` and `CATALOG_DEPENDENCY_CATALOG_UNDECLARED`, and its
export did not pass validation. Each catalog it uses is now declared and pinned to the release this
tenant has of it. Releases received before this fix keep their declarations as they are.
