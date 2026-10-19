---
type: feat
scopes: [catalog, generation]
audience: user
breaking: true
title: A catalog depends on the catalogs it uses, and a published version renders with the versions it was published with.
---

A catalog that uses another catalog's themes, stencils, fonts, images or code lists now depends on it, and each published version records the exact version of every catalog it uses, directly or through them. It renders with those versions, whatever that catalog publishes or an environment deploys later, and installing or importing a catalog keeps the versions its publisher used. In the web UI you do not manage version ranges: publishing records a catalog you picked something from, and the **Dependencies** page lists what the catalog uses and which catalogs use it. When a catalog you use publishes a newer version, Epistola checks it against what you actually use: if it fits, your next publish moves to it; if it changes something you use (a stencil lost a placeholder your template fills, say), you stay on your version and are told what and why. Moving to a newer version counts as a breaking change of your catalog only when it breaks something you use. When a stencil or theme you use was changed in its own catalog and not published yet, publishing offers to publish that catalog too, so what is published is what you previewed. Over REST and MCP a dependency pins an exact version, or, in a draft, names a draft of that catalog to build on, which is published first when you publish with its dependencies; releasing refuses a reference to a catalog that is not declared. The upgrade from 1.x declares what existing catalogs use.
