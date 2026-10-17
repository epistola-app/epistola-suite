---
type: feat
scopes: [catalog]
breaking: false
title: The upgrade to 2.0 declares the dependencies of existing catalogs.
---

A catalog from 1.x references other catalogs, such as the system catalog's fonts and attributes, without declaring them, and 2.0 refuses to release a catalog with an undeclared reference. Shortly after the upgrade, a background task declares, for every authored catalog that existed before it, each catalog its content references, with version `*` and locked to the highest release there is. Catalogs created after the upgrade are left to their authors. Nothing needs to be done; the log names every catalog it declared for.
