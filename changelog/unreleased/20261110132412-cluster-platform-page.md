---
type: fix
scopes: [security, cluster]
breaking: true
title: The cluster page is a platform page, for the installation administrator and the platform observer.
---

The cluster page lived in every tenant (`/tenants/{tenant}/cluster`), and any member of a tenant,
whatever their role, could open it there. It shows the whole installation: its nodes, and the
scheduled tasks and timers it runs for every tenant. It is now Platform → Cluster
(`/platform/cluster`), open to the installation administrator and the platform observer; only the
administrator forgets a node. The tenant URL is gone, and the page left the tenant's Operations menu.
Collecting a tenant's stored release content by hand stays a tenant action.
