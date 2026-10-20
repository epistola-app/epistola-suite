---
type: fix
scopes: [api, generation]
title: A document, a job item and a collected result always name the catalog version they were rendered from.
---

`versionId` is now the id of the catalog version rendered for a release as well as for a draft, where before it was only filled for a draft and read as missing data. Documents, requests and results made since the 2.0 upgrade are filled in from the release they name; only documents from before 2.0 have none.
