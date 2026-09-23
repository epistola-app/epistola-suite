---
type: fix
scopes: [backup]
issues: [990]
title: Restoring a tenant that has released a catalog no longer fails.
---

A tenant backup dumped and re-inserted every column of every table it carries, including generated
ones. PostgreSQL refuses an INSERT that names a generated column, so once a catalog had a release —
whose version is stored alongside parsed sort components the database computes — restoring that
tenant failed outright. Generated columns are now left out of the backup and recomputed on restore,
which is what they are for.
