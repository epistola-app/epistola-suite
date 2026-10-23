---
type: feat
scopes: [api, generation]
breaking: true
title: Over REST, a catalog's versions are its releases, a draft being a release in status draft.
---

The REST API follows the contract's renaming. Paths move from `…/versions/{catalogVersion}/…` to `…/releases/{release}/…` (still `next`, a release id or a release number), and the operations become `listCatalogReleases` (drafts with `?status=draft`), `getCatalogRelease`, `createCatalogRelease`, `updateCatalogRelease`, `deleteCatalogRelease`, `deployCatalogRelease` and `undeployCatalogRelease`; `releaseCatalogDraft` and `previewCatalogDraftRelease` keep their names. Fields follow: `versionId` is `releaseId`, `releaseVersion` is `version`, `highestReleaseVersion` is `highestVersion`, `draftName` is `releaseName`, a deployment's `kind` is `status` (`draft` | `released`), and a release response carries `release` instead of `catalogVersion`. A catalog no longer lists its `drafts`. Documents, generation items and collected results report `version` and `releaseId`. A variant selection attribute is a `key` with an optional `catalogKey`; without it, it names an attribute of the template's own catalog, where a bare key used to match nothing. **Action on upgrade:** move REST clients to the new paths and field names.
