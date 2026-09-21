---
type: feat
scopes: [catalog]
audience: user
title: A theme key may be as long as every other resource key.
---

`THEME_KEY` had been 20 characters since themes shipped, and nothing else in the schema is 20 — templates, stencils, attributes and variants are 50, fonts and code lists 64. The bound was never a property of themes: `corporate-identity-2026` is 23 characters and an ordinary theme name that the suite refused. It is now 50, matching the majority. Widening is data-preserving by construction, and catalog wire v7 publishes the same bound, whose own rule is that a maximum may be relaxed later but never tightened once catalogs use the extra room — so this lands before v7 releases rather than moving the published schema afterwards.
