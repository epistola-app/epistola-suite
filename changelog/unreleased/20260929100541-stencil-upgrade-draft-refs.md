---
type: fix
scopes: [stencils]
issues: [1024, 1032]
title: Upgrading a template's stencils now makes it publishable, and only to published versions.
---

A template whose stencils were inserted while the stencil was still a draft stayed unpublishable
after its instances were upgraded to the published stencil version: the upgrade kept the old draft
reference, and publishing refused it. The upgrade now replaces the draft reference, and it refuses a
target stencil version that is not published, so an instance can no longer be pinned to content
that is still changing.
