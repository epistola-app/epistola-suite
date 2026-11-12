---
type: feat
scopes: [upgrade, catalog]
breaking: true
title: A 1.x installation upgrades to 2.0 in one migration that only goes forward.
---

Templates, stencils, themes, fonts, images, attributes and code lists are converted. Each catalog keeps its work in progress as its next release, and gets a draft **Published in 1.x** beside it with what was last published. The upgrade publishes and deploys nothing, so documents cannot be generated until you publish each catalog and deploy it; the tenant home lists what is left. Before a single migration runs, the migration step checks that every catalog can be published, and stops with a report, leaving 1.x untouched, when catalogs use each other or use a catalog the tenant lacks. The 1.x tables are dropped and the upgrade cannot be rolled back. **Action on upgrade:** stop every 1.x node, take a database backup, drain the generation queue and follow the Upgrades guide, "From 1.x to 2.0", which also says why some documents can look different afterwards.
