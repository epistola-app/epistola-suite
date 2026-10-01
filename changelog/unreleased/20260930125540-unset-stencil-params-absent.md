---
type: fix
scopes: [generation, stencils]
audience: user
issues: [1031]
title: "`$exists(params.x)` inside a stencil is false when the parameter has no value."
---

Every declared stencil parameter used to be present in `params`, as null when its binding found nothing or it was optional and unbound, so `$exists(params.x)` was always true and a condition such as `$exists(params.medewerker)` printed an empty row. A parameter without a value is now absent, as a missing field is in template data. Template versions published from this release get the new behaviour; versions published earlier keep rendering as they did. Drafts and previews show it at once.
