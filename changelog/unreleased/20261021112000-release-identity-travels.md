---
type: feat
scopes: [catalog, api]
audience: user
title: A release keeps its id, its rendering defaults and the release it was made from wherever it goes.
---

Every catalog archive a release is written to (an export, the Exchange, a registry) names its release id, the rendering defaults it renders with (`release.metadata`, `{"app.epistola.suite": {"renderingDefaultsVersion": 6}}`) and the release it was made from (`release.base`), none of which changes its fingerprint. An installation that receives the release keeps all three, so the release has the same id everywhere, renders with the defaults it was cut with, and can be merged against the right release; metadata of other producers is kept as it came. A received release whose id another release of the catalog already has here is refused, since one id names one release. A release that renders with rendering defaults this installation does not have is refused before anything is installed, and the import says to upgrade Epistola first (REST 400 `catalog-rendering-defaults-too-new`).
