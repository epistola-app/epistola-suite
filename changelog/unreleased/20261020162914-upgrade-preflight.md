---
type: feat
scopes: [upgrade]
audience: dev
title: The upgrade from 1.x refuses to start when catalogs use each other or use a catalog the tenant does not have.
---

2.0 gives every catalog a first release after the catalogs it uses, which it cannot do for catalogs that use each other (a dependency cycle) or that use a catalog the tenant does not have; those catalogs would have stayed without a release, unable to render. The migration step now checks for both before a single migration runs and, when it finds either, exits non-zero with the tenants, catalogs and resources involved. The database is left as it was, so 1.x keeps working while the catalogs are fixed there. The checks are listed in the Upgrades guide; one can be skipped by id with `epistola.migration.preflight.skip` (`EPISTOLA_MIGRATION_PREFLIGHT_SKIP`) once you have read why it refused. **Action on upgrade:** if the migration step stops with this report, fix what it names in 1.x (move what catalogs share into one of them, or into a catalog they all use; install a missing catalog) and run the upgrade again.
