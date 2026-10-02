---
type: feat
scopes: [environments, generation, catalog]
audience: user
breaking: true
title: An environment deploys a catalog release, replacing per-template activations.
---

Each environment now serves one release per catalog, chosen on the catalog page's new Deployments
card or on the environment's own new page: deploy a release to an environment, deploy another to
move it, deploy an earlier one to roll back. The environment page also shows what it served over
time, every deploy and undeploy with who made it, and offers an earlier release again in one click. Generation and preview that name an environment render the release it serves for the
template's catalog, bound when the request is accepted, and fail with a clear error when it serves
none. A release an environment serves, or another release records as a dependency, cannot be
deleted or have its content forgotten, and an environment that serves releases cannot be deleted.
Per-template activations are gone, with the template's Deployments tab; existing activations are not
carried over, so every environment starts with nothing deployed. The REST operations for
activations, and publishing a version to an environment, now answer that the operation is not
implemented, until the 2.0 contract removes them. Subscribed catalogs now list the releases they
installed.
