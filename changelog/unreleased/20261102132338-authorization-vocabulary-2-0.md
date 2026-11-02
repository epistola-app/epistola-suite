---
type: feat
scopes: [security, api]
audience: user
breaking: true
title: Roles grant what the 2.0 contract says they grant, and generated documents are no longer every viewer's to read.
---

Content viewers no longer read generated documents, which carry personal data. Document generators
read what they generate, and a new role, `document-reader`, reads documents without generating. A
new platform role, `installation-administrator`, takes the site banner, data sharing and cluster
maintenance from `tenant-manager`. `platform-observer` now reads diagnostics and the audit trail in
every tenant. The Exchange connection and quality providers need their own permission
(`INTEGRATIONS_MANAGE`), and so do feedback and load tests. `TENANT_USERS` is now
`CREDENTIALS_MANAGE`. Review who holds which role in your identity provider, and which scopes your
API keys have, before upgrading (see the upgrade notes).
