---
type: fix
scopes: [support, catalog]
audience: user
title: The catalog mirror no longer lets the system catalog be marked "Never share".
---

Every new catalog depends on the system catalog, so marking it "Never share" kept nearly every
release from the catalog mirror. The catalogs Epistola supplies (the system catalog and the bundled
ones) can no longer be marked "Never share": the catalog mirror page leaves the option out and says
why, and setting it is refused with `SUPPLIED_CATALOG_NOT_WITHHELD`.
