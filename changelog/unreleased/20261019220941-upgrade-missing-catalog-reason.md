---
type: fix
scopes: [catalog, upgrade]
title: A catalog the 1.x upgrade cannot release because it uses a catalog the tenant lacks says which one.
---

When what a catalog published in 1.x uses another catalog the tenant no longer has, its 2.0 upgrade waits, as the upgrade guide describes. The reason recorded for it read "dependency names no namespace; dependency names no registry"; it now names the missing catalog and says to install it or change what uses it.
