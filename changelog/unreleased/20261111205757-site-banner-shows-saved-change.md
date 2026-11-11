---
type: fix
scopes: [banner]
audience: user
title: A saved site banner shows at once, also when a page was drawn while it was being saved.
---

Saving the site banner cleared its cache before the change was committed. A page drawn in that
moment read the old banner and cached it, and that node then showed the old banner for up to a
minute. The cache is now cleared again once the change is committed.
