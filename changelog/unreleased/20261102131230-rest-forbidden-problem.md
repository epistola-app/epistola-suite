---
type: feat
scopes: [api, security]
audience: dev
breaking: true
title: A refused request answers the contract's one 403 type, forbidden, naming what was refused.
---

The REST API answered a refusal with one of three types the contract never registered:
`permission-denied`, `access-denied` and `platform-access-denied`. Every refusal is now
`https://epistola.app/errors/forbidden`. Its members say what was refused: `requiredPermission` and
`tenantKey` for a missing permission, `tenantKey` alone for no access to the tenant, and
`requiredPlatformRole` for a platform role. Permissions and roles are named as the contract names
them (`TEMPLATE_EDIT`, not `template-edit`), and `tenantId` and `requiredRole` are gone. An
integration that matched the old types or members must match these instead.
