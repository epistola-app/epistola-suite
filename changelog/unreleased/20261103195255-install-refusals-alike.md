---
type: fix
scopes: [api, catalog]
title: Installing a subscribed catalog's release answers an Exchange or registry refusal as the registry install does.
---

`installCatalogRelease` for a catalog installed from Epistola Exchange or a registry let some of the source's refusals through as a `validation-error`: an archive that does not match what was announced or is too large, a release the source no longer has, or a catalog key taken under another source. It now answers them as `installRegistryRelease` does: `not-found` for a missing release, `conflict` for a clash, `bad-request` for a rejected archive.
