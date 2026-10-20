---
type: fix
scopes: [ui]
title: The bar at the top of a catalog page updates after an inline save, instead of still saying nothing has changed.
---

Renaming a template, changing its theme or PDF/A setting, and other saves made in place left the context bar saying "Everything is published" or "No changes yet" until the page was reloaded. The bar now refreshes after every save, so the change shows up as not published right away.
