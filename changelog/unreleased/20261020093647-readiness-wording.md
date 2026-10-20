---
type: fix
scopes: [ui]
title: A variant or stencil not marked ready reads "Not checked yet" in a neutral badge, since publishing checks and marks it.
---

Variants and stencils showed a yellow "Not ready" badge, and a changed stencil said the catalog could not be released until it was marked ready. Publishing checks and marks everything ready itself, so it is not a required step: the badge is now neutral and reads "Not checked yet", and the stencil says that publishing checks it and marks it ready. Marking ready by hand still checks it early.
