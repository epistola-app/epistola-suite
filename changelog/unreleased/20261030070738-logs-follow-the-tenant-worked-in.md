---
type: fix
scopes: [logs]
title: What someone does in a tenant is logged under that tenant.
---

A signed-in user's actions were logged under the tenant they signed in to, or, for a user signed in
through single sign-on, under no tenant at all, which showed them in every tenant's Logs page. Work
in a tenant's pages is now logged under that tenant, for every tenant the user may act in.
