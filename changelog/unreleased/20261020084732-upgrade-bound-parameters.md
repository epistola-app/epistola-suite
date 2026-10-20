---
type: fix
scopes: [upgrade, stencils]
title: The 2.0 upgrade keeps stencil parameters a template binds when the stencil's latest version no longer declares them.
---

In 1.x a template rendered its own copy of the stencil version it used, with that version's parameters. After the upgrade it uses the stencil as it is now, so a parameter the template binds but the latest stencil version no longer declares rendered empty: a letterhead lost its company name on every page. The upgrade now declares such a parameter again on the stencil, as optional, and lists it in the upgrade report.
