---
paths:
  - "**/db/migration/**"
---

# Flyway migrations

The database is **never reset between versions**, from 1.0.0-RC1 onward. Every schema change is a
forward, data-preserving migration. This is not negotiable for any feature maturity.

- **Module-owned**: `<module>/src/main/resources/db/migration/<module>/`, named
  `VYYYYMMDDHHMMSS__<module>_<desc>.sql`. Generate the version with `date -u +V%Y%m%d%H%M%S`.
- All modules' migrations merge onto **one global Flyway namespace** at runtime, so versions must be
  globally unique and ordered. A non-core migration that FKs to — or uses a `DOMAIN` from — a core
  table must timestamp **after** that core migration.
- **Never edit a merged migration.** Add a new timestamped file. Folding `ALTER`s back into the
  original `CREATE` is no longer permitted; the RC1 consolidation was the last one.
- **No destructive migrations**: nothing that drops or resets user data.
- A new tenant-scoped table must be classified for backup, or
  `TenantTableTopologyDriftIntegrationTest` fails.
- Prefer a lookup table over a `CHECK` constraint for extensible value sets — asset and font media
  types are seeded `asset_types` rows, so adding one is an insert, not a constraint change.

Background: [`docs/migrations.md`](../../docs/migrations.md).

## Verify

```bash
./gradlew checkMigrationVersions        # uniqueness, ordering, and that merged files are untouched
./gradlew :apps:epistola:integrationTest --tests "*SchemaHygieneAppTest*"
```

`checkMigrationVersions` compares against the base branch, so it catches a version that sorts before
something already merged — the failure mode that stops installations booting.
