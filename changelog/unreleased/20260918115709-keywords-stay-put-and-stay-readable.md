---
type: fix
scopes: [catalog]
audience: user
title: Keywords keep a predictable order and no longer stretch the catalog page.
---

A single long keyword used to push the whole catalog page sideways — measured at 218 pixels of
horizontal scroll, so every column on the page moved, not just the keyword. Keywords and discovery
attributes now stay inside their card and shorten with an ellipsis, with the full value on hover.

Keywords are also bounded now: at most 20 keywords of 30 characters each, which the edit dialog
states and the server enforces. Existing catalogs are unaffected.

Their order was previously whatever the stored data happened to return, which looked sorted without
being sorted — keywords starting with a digit came last. They are now shown in a fixed order,
digits first and then alphabetically ignoring case, and the edit dialog uses the same order so
opening it no longer rearranges them.
