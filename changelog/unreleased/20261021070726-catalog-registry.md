---
type: feat
scopes: [catalog, mcp]
audience: user
title: Catalog releases can move between installations through a container registry (alpha).
---

An installation can publish a released catalog version to a container registry (Harbor, GitLab, ACR, ECR, zot and others) and another installation can get it from there, for example from acceptance to production. Publishing refuses a number the registry already holds with other content; getting a version checks the download against its digest and recomputes the fingerprint, as importing a file does. Available in the web UI (a release's page, and the catalogs page) and over MCP. Nothing changes until an operator configures `epistola.catalog.registries`; see the catalog registries documentation, including how to make tags immutable.
