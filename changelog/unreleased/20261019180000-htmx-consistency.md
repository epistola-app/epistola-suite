---
type: fix
scopes: [ui]
audience: user
title: Refused dialog actions, redirects and directly opened dialogs behave consistently.
---

Publishing, upgrading, promoting long-term work, following a draft, ignoring a quality finding and creating a tenant now answer a refusal with an error status, so nothing on the page takes it for success. A dialog's address opened directly (a bookmark, a new tab) shows its catalog's page instead of a bare fragment. Refreshing support entitlements without JavaScript lands back on the support page instead of a blank one.
