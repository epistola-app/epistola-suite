---
type: fix
scopes: [support]
audience: user
title: The setup for every tenant no longer fails when it shares catalogs while tenants are confidential.
---

The platform page offered to share catalogs in the setup for every tenant even while the installation
keeps its tenants confidential to Epistola, and saving that failed with an internal server error. Those
choices are now disabled there with the reason, as on a tenant's own page, and a save that still
carries one says why and saves nothing.
