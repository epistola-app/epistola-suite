---
type: feat
scopes: [db]
audience: user
breaking: true
title: PostgreSQL 18 is now the minimum.
---

Catalog resource identities are minted with `uuidv7()`, which 17 does not have — so identities are time-ordered rather than random, and a new one lands at the right-hand edge of the index instead of anywhere in it. The first identity migration checks `server_version_num` and stops with a named error before taking any lock, so an older server costs a failed migration rather than a half-applied one. Upgrade the database before the suite; see [Upgrading](docs/upgrades.md#postgresql-18-is-required). Which PostgreSQL versions a release supports is still nowhere machine-readable — [#909](https://github.com/epistola-app/epistola-suite/issues/909) tracks declaring and publishing that properly.
