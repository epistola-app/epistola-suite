---
type: feat
scopes: [catalog, exchange, api, mcp]
audience: user
title: Catalog releases move between installations through a container registry (alpha).
---

An installation can publish a release to a container registry (Harbor, GitLab, ACR, ECR, zot and others), and another can install it from there, for example from acceptance to production. A registry refuses a number it already holds with other content, and an install checks the download's digest and fingerprint. Epistola Exchange is offered beside container registries as a release registry, in the web UI, over MCP and over REST. Nothing changes until an operator configures `epistola.catalog.registries`; see the catalog registries documentation.
