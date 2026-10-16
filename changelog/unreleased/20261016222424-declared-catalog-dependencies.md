---
type: feat
scopes: [catalog, generation]
audience: user
breaking: true
title: A catalog declares the catalogs it depends on, and a release renders with the releases it was cut with.
---

A catalog that uses another catalog's themes, stencils, fonts, images or code lists now declares that catalog once, with a version range such as `^1.4.0`. Each declaration is locked to one release of the dependency, which only moves when you resolve the dependencies again. Releasing refuses a reference to a catalog that is not declared, or a declaration no release matches, and records which release of each dependency the release was cut with. A release then renders those releases, not whatever the dependency released or an environment deployed later. Installing or importing a catalog keeps the releases its publisher cut it with.
