---
type: feat
scopes: [catalog]
audience: user
title: Organising catalogs is stable and on by default, and works while a catalog has several drafts.
---

Organise catalogs (`resource-relocation`, now Stable and on by default) renames and moves resources: it changes the draft you work in and the next version of every other catalog involved; it is no longer refused while a catalog has parallel work. Other drafts keep their copy at the old address and are named in the preview, and the resource keeps its history at its new address. The preview says what each catalog's next release becomes and in which order to release them, **Rename…** and **Move to another catalog…** are on every resource and its row in a list, and **Create a shared catalog…** is on the catalogs list. What now uses a moved resource depends on its new catalog's next version. Merging a fix from a draft that was left alone recognises what moved: a fix naming a moved theme follows it, and a fix to the moved theme says where it went.
