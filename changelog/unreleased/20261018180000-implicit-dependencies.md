---
type: feat
scopes: [api, environments]
title: Deploying a catalog version uses the versions of other catalogs it depends on, without deploying them.
---

A published version is pinned to exact versions of the catalogs it uses and renders them from the tenant's copy, so deploying it no longer requires the environment to have those deployed: `deployRelease` succeeds and the environment uses them implicitly, listed under **Comes along** on the environment page. `includeDependencies=true` now also deploys them explicitly. Only a pinned version nothing could render from (`UNAVAILABLE`, `CONTENT_NOT_RETAINED`) refuses the deployment with `release-dependencies-missing`; `NOT_DEPLOYED` and `NOT_INSTALLED` are no longer returned. Deploying a version in place of another no longer keeps the replaced one deployed because another catalog depends on it: that catalog keeps using it implicitly.
