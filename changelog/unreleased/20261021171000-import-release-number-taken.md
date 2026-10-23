---
type: feat
scopes: [catalog, api]
audience: user
title: Importing a release the tenant has as another answers release-number-taken.
---

When `importCatalog` (and every install that imports an archive: `installCatalogRelease`,
`installRegistryRelease`) is refused because the tenant has that number with other content, or that
release id as another release or draft (one number and one id name one release), REST answers 409
`release-number-taken` instead of a plain `conflict`. Another catalog under the same key stays a
`conflict`.
