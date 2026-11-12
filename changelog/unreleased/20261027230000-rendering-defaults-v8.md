---
type: feat
scopes: [generation, themes]
audience: user
title: Releases published in 2.0 render with rendering defaults V8, with block spacing from the theme.
---

A theme can set the space above and below every block (`defaultBlockStyles`); without it a block keeps 6pt below it, where the old default grew with the theme's spacing unit. Nested blocks' margins collapse into the larger one, as on the web. Drafts render with the newest defaults, a release keeps the defaults it was published with, and moving to new defaults is a change the publish dialog names. Every catalog's first 2.0 release renders with V8, including the one that puts back what 1.x published, so spacing can change: check documents before deploying (Upgrades guide).
