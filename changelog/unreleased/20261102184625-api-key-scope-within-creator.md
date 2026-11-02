---
type: fix
scopes: [security, api-keys]
audience: user
title: An API key can carry only roles its creator holds in the tenant.
---

Creating an API key needs the administrator role, but the key's roles were chosen freely, so an
administrator who does not publish could create a key that publishes and deploys. Creating a key now
refuses any role its creator does not hold, and the dialog offers only those. Existing keys keep their
roles: review them at `/tenants/{tenant}/api-keys`. Security advisory EPIS-2026-006
(GHSA-rp93-9966-v2vh).
