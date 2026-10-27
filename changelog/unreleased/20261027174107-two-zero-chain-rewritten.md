---
type: refactor
scopes: [migrations]
audience: dev
breaking: true
title: The 2.0 database migrations run from the last 1.x schema straight to 2.0; rebuild databases from earlier 2.0 builds.
---

The migrations of the 2.0 alphas are replaced by one chain of steps, each doing one thing to a 1.x
database (docs/migrations.md, "The 2.0 chain"); a 1.x installation upgrades as before. A database
migrated by an earlier 2.0 build, such as a development or test database, cannot migrate on: rebuild
it from a 1.x database or from scratch. No released version is affected.
