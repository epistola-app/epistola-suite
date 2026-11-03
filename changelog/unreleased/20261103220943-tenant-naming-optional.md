---
type: fix
scopes: [ui, tenants]
audience: user
title: A new tenant needs a name and an ID; naming its catalogs is optional.
---

New tenant asked for a namespace and an authority before a tenant could exist, though they matter
only once its catalogs leave the installation. They are now an optional, closed **Catalog naming**
section: left empty, the tenant's catalogs are named under `local` and a namespace can be granted later
under Namespaces. Opened, the namespace follows the tenant ID and a line shows what the catalogs will
be called. The authority is no longer filled in with `exchange.epistola.app`: Exchange namespaces come
through the tenant's Exchange connection, so the field asks for the organisation's own domain.
