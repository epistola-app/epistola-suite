---
type: feat
scopes: [quality]
audience: user
issues: [1028]
title: Quality checks report a document with more than one address block.
---

A document supports one address block, and the editor stops a second one being inserted directly, but one can still arrive inside an included stencil, over REST or MCP, or by catalog import. Such a document is saved and renders with a single address block; the new "Layout" quality source now reports it on every address block involved, so the author knows only one is used. The limit comes from the component registry's `maxInstancesPerDocument`.
