---
type: fix
scopes: [catalog]
title: The catalogs page no longer offers to export the system catalog, which failed with a message about altered content.
---

Exporting the system catalog failed with "its retained content has been altered". Nothing was altered: the system catalog keeps its seeded fonts, which the archive it comes from does not carry, so what it keeps never matches that archive's fingerprint. It is not offered for export any more, and an export of it is refused saying why; every installation has it from the suite itself.
