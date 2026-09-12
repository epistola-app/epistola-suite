---
type: docs
scopes: [exchange, catalog]
audience: dev
title: ADR 0021 and an installation guide for the inbound Exchange direction.
---

ADR 0021 records why release discovery is polled rather than pushed, why the answer is persisted and kept out of tenant backups, why there is no catalog-to-Exchange port, and why Exchange's own bulk upgrades feed is not used — it needs scopes every existing connection would have to reauthorize for, and reporting each tenant's installed catalogs is a disclosure worth making deliberately. Also fixes index entries PR #870 left behind: `docs/README.md` had no Exchange row at all, and `docs/adr/README.md` was missing 0016, 0017 and 0018.
