---
type: feat
scopes: [upgrade, environments]
title: The tenant home says why documents cannot be made yet, and the 2.0 upgrade starts at once.
---

The tenant home names environments that use no catalog yet, where every document request fails, with a link to deploy a version, and says how many catalogs are still being prepared for Epistola 2.0. Both are what an installation looks like right after upgrading from 1.x, and what a new environment looks like.

The 2.0 upgrade of 1.x catalogs now runs as soon as the application starts instead of five minutes later, logs when it has finished, and reports the catalogs still waiting as the metric `epistola_catalog_upgrade_queued`. The upgrade guide gives the queries for a catalog that stays queued and for the requests to resubmit, and notes that with the HPA on the application must be scaled back up after the upgrade.
