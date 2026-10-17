---
type: feat
scopes: [environments, generation, api]
breaking: true
title: An environment serves several releases of a catalog, and deploying checks the releases it renders with.
---

Deploying a release now adds it beside the releases of its catalog the environment already serves, and undeploying names the one release to stop serving. Generation and preview naming an environment render the highest release it serves, or the highest that satisfies the request's `version`, which is now a version selector (`1.4.0`, `^1.4.0`, `>=1.0.0 <2.0.0`) and combines with `environmentKey`. Deploying a release whose dependency releases the environment does not serve is refused with `release-dependencies-missing` (409), listing each one and why; `includeDependencies=true`, or "Deploy with its dependencies" on the environment and catalog pages, deploys them along, all or nothing. A dependency release the tenant does not have is not fetched from its registry.
