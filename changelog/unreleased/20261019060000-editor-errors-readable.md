---
type: fix
scopes: [editor]
audience: user
title: The editor says what failed, shows all its tabs, and its guided tour is on by default.
---

A failed save reads "Not saved:" with the reason and offers "Retry save", and a sign-in or proxy page in place of an answer is explained instead of shown as markup. The side panel's tabs wrap instead of hiding Quality. The canvas no longer shows internal block ids, the unit menus explain "sp" and "pt", and the formatting menu no longer covers a block's delete button. The `editor-walkthrough` toggle defaults to on, so new users see the Guide button; switch it off per tenant or with `epistola.features.editor-walkthrough=false`.
