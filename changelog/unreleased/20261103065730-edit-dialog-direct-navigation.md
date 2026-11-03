---
type: fix
scopes: [ui]
title: Opening a variant's or an attribute's edit dialog as a page no longer fails.
---

The edit dialogs of a variant and of an attribute are opened from their page. Opening one's address directly, from a bookmark or by reloading, answered a server error; it now opens the template or the attribute list the dialog belongs to.

A value containing a NUL character, which the database cannot store, answers a 400 with an explanation instead of a server error.
