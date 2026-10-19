---
type: feat
scopes: [catalog, exchange]
audience: user
breaking: true
title: A catalog's namespace is where it publishes on Exchange, and it is fixed once a release reaches Exchange.
---

A catalog now has one namespace, part of its name, and it publishes to Epistola Exchange under that namespace; there is no separate Exchange namespace setting. On the upgrade to 2.0 a catalog already set to publish to an Exchange namespace takes that namespace, and one installed from Exchange takes its publisher's. A new catalog starts on the tenant's default Exchange namespace when there is one. The publish dialog shows the full name a release is published as, and asks for a granted namespace when the catalog's is not one the Exchange connection grants. Once a release has reached Exchange the namespace is fixed: moving a published catalog to another namespace, which 1.x allowed after a confirmation, is no longer possible, because publishing it elsewhere would make it another catalog. A catalog recreated under the same key returns to the namespace it published under.
