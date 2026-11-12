---
type: feat
scopes: [security, api]
audience: user
breaking: true
title: Roles grant what the 2.0 contract says, and generated documents are no longer every viewer's to read.
---

Content viewers no longer read generated documents, which carry personal data; `document-reader` reads them without generating. `installation-administrator` takes the site banner, data sharing and the cluster page, now Platform → Cluster, from `tenant-manager`, and `platform-observer` reads diagnostics and audit in every tenant. `CATALOG_RELEASE` and `ENVIRONMENT_DEPLOY` replace `TEMPLATE_PUBLISH` and `STENCIL_PUBLISH`; the content publisher holds both. `TENANT_USERS` is `CREDENTIALS_MANAGE`, and the Exchange connection, quality providers, feedback and load tests need `INTEGRATIONS_MANAGE`. Every REST operation, MCP tool and UI control asks exactly the permission it declares; previewing in the editor needs `TEMPLATE_VIEW`. **Action on upgrade:** review role assignments and API key scopes.
