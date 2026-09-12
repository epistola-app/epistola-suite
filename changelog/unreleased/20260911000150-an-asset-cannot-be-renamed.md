---
type: fix
scopes: [catalogs]
audience: user
title: An asset cannot be renamed.
---

Its key is a generated UUID, and an unqualified image reference resolves by that id alone — with no catalog to find an alias with, a rename would leave the image unreachable. `MovableResource` now records which types have a generated key, and the preview refuses the rename rather than accepting one that breaks content.
