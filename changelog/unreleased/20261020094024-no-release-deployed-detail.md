---
type: fix
scopes: [generation, api]
title: A generation request for an environment that serves nothing says how to deploy, in the words the application uses.
---

The `no-release-deployed` problem said an environment "serves no release" and to "deploy a release there first". It now says it serves no published version of the catalog (matching the requested range, when there is one) and where to deploy one: on the environment's page in Epistola, or with `deployCatalogVersion`.
