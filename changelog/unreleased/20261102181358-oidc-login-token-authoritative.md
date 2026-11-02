---
type: fix
scopes: [security, auth]
audience: user
title: An OIDC login grants only the tenant roles the identity provider sends.
---

When a user's token carried no tenant or global roles, the login fell back to the tenant roles
stored by their earlier logins, so taking every Epistola group away from a user at the identity
provider did not take their access away. The token is now authoritative: a login without roles
grants no tenant access. Security advisory EPIS-2026-005 (GHSA-mcr3-3w4v-2g99).
