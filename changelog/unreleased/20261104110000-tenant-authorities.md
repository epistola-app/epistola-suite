---
type: feat
scopes: [ui, catalog, tenants]
audience: user
title: A tenant's administrator manages its namespaces under Settings, Authorities & namespaces.
---

Namespaces name a tenant's catalogs, but only a platform administrator could see or change them, on
a page outside the tenant. They are now the tenant's own: **Settings, Authorities & namespaces**
lists `local`, the namespaces of the Epistola Exchange connection and each of the organisation's
domains with its namespaces, and the tenant's administrator adds a namespace under a domain or
removes one. Exchange namespaces still come only from the Exchange connection. The platform tenant
list no longer has a **Namespaces** action, and **New tenant** asks for a name and an ID only: a new
tenant's catalogs start under `local`.
